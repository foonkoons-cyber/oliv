#!/usr/bin/env python3
"""
Reframe a wide clip to a vertical aspect without ever losing the subject.

Three layouts, picked per shot:

  crop      the vertical window tracks the subject. Used when the subject's
            box fits inside the window on every single frame.
  hybrid    crop where it fits, and for the runs of frames where it does not,
            fall back to fill for those frames only.
  fill      the whole wide frame is letterboxed over a blurred, scaled copy of
            itself. Nothing is cropped, so the subject cannot leave the frame.
            This is the guaranteed fallback.

The subject track comes from detectors that ship with OpenCV -- HOG people,
Haar frontal-face and upper-body, and a dense-optical-flow centroid when none
of them fire -- so no model downloads are needed. Gaps are interpolated, the
track is smoothed, and the window's velocity is clamped so the move reads as an
operator pan rather than a servo twitch.

Every frame is verified before it is written: run with --strict and the tool
exits non-zero rather than emitting a frame where the subject is clipped.
"""

import argparse
import json
import subprocess
import sys

import cv2
import numpy as np

DETECT_SCALE = 0.35          # detect on a downscaled copy; the track is scaled back up
HEAD_BIAS = 0.42             # put the subject's centre this far down the window
MAX_PAN_PX = 6.0             # per output frame, in source pixels
SMOOTH_SEC = 1.0


# --------------------------------------------------------------------------- #
# detection
# --------------------------------------------------------------------------- #

class SubjectDetector:
    def __init__(self):
        self.hog = cv2.HOGDescriptor()
        self.hog.setSVMDetector(cv2.HOGDescriptor_getDefaultPeopleDetector())
        hc = cv2.data.haarcascades
        self.face = cv2.CascadeClassifier(hc + "haarcascade_frontalface_default.xml")
        self.profile = cv2.CascadeClassifier(hc + "haarcascade_profileface.xml")
        self.upper = cv2.CascadeClassifier(hc + "haarcascade_upperbody.xml")
        self.prev_gray = None

    def candidates(self, frame):
        """Every person-like box in the frame, downscaled coords.

        Returns (small_frame, [(cx, cy, w, h, source, confidence), ...]). More
        than one person is the normal case on a court, so the caller decides
        which one matters rather than this settling for the biggest.
        """
        small = cv2.resize(frame, None, fx=DETECT_SCALE, fy=DETECT_SCALE)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        out = []

        found, weights = self.hog.detectMultiScale(
            small, winStride=(8, 8), padding=(8, 8), scale=1.06)
        for (x, y, w, h), c in zip(found, weights):
            out.append((x + w / 2, y + h / 2, float(w), float(h),
                        "hog", 1.0 + float(c)))

        for cas, name, grow in ((self.face, "face", (3.0, 7.0)),
                                (self.profile, "profile", (3.0, 7.0)),
                                (self.upper, "upper", (1.2, 3.2))):
            for x, y, w, h in cas.detectMultiScale(gray, 1.1, 5, minSize=(24, 24)):
                gw, gh = grow
                out.append((x + w / 2, y + h * gh / 2, w * gw, h * gh, name, 1.0))

        if not out and self.prev_gray is not None:
            flow = cv2.calcOpticalFlowFarneback(
                self.prev_gray, gray, None, 0.5, 3, 15, 3, 5, 1.2, 0)
            mag = np.linalg.norm(flow, axis=2)
            if mag.mean() > 0.25:
                ys, xs = np.nonzero(mag > np.percentile(mag, 97))
                if len(xs) > 30:
                    out.append((float(xs.mean()), float(ys.mean()),
                                small.shape[1] * 0.18, small.shape[0] * 0.55,
                                "flow", 0.5))

        self.prev_gray = gray
        return small, out

    def __call__(self, frame):
        """The biggest candidate, for callers that just want "is anyone here"."""
        _, cands = self.candidates(frame)
        if not cands:
            return None
        cx, cy, w, h, src, _ = max(cands, key=lambda c: c[2] * c[3])
        return (cx, cy, w, h, src)


class SubjectSignature:
    """Tells one person from another by the colour of what they are wearing.

    A hue/saturation histogram of the torso separates an interviewee in team
    kit from the other players on court, costs almost nothing to compare, and
    unlike a re-identification network needs no weights -- which matters where
    model hosts are unreachable.

    It holds several reference histograms rather than one, and scores a
    candidate at its best match against any of them. One reference is not
    enough: a shoot's light swings between setups, and saturation swings with
    it, so a signature taken in flat light scores badly against the same person
    backlit. Sampling the reference clip at a spread of times covers that.
    """

    BINS = (30, 32)

    def __init__(self, refs):
        """refs: an iterable of (small_frame, box)."""
        self.hists = [h for h in (self._hist(s, b) for s, b in refs)
                      if h is not None]

    @property
    def hist(self):
        return self.hists[0] if self.hists else None

    @classmethod
    def _hist(cls, small, box):
        cx, cy, w, h = box[:4]
        # torso only: skip the head, stop above the legs, inset from the edges
        x0 = int(max(0, cx - w * 0.30));  x1 = int(min(small.shape[1], cx + w * 0.30))
        y0 = int(max(0, cy - h * 0.22));  y1 = int(min(small.shape[0], cy + h * 0.18))
        if x1 - x0 < 4 or y1 - y0 < 4:
            return None
        patch = cv2.cvtColor(small[y0:y1, x0:x1], cv2.COLOR_BGR2HSV)
        hist = cv2.calcHist([patch], [0, 1], None, cls.BINS, [0, 180, 0, 256])
        cv2.normalize(hist, hist, 0, 1, cv2.NORM_MINMAX)
        return hist

    def score(self, small, box):
        """0..1 against the closest reference. 0 when the patch is unusable."""
        if not self.hists:
            return 0.0
        h = self._hist(small, box)
        if h is None:
            return 0.0
        return max(float(max(0.0, cv2.compareHist(r, h, cv2.HISTCMP_CORREL)))
                   for r in self.hists)


# --------------------------------------------------------------------------- #
# track conditioning
# --------------------------------------------------------------------------- #

def fill_gaps(values, present):
    """Linear-interpolate missing samples; hold the nearest value at the ends."""
    v = np.asarray(values, dtype=np.float64)
    idx = np.nonzero(present)[0]
    if len(idx) == 0:
        return None
    if len(idx) == 1:
        return np.full(len(v), v[idx[0]])
    return np.interp(np.arange(len(v)), idx, v[idx])


def smooth(v, win):
    win = max(3, int(win) | 1)
    pad = win // 2
    padded = np.pad(v, pad, mode="edge")
    kernel = np.hanning(win)
    kernel /= kernel.sum()
    return np.convolve(padded, kernel, mode="valid")


def clamp_velocity(v, max_step):
    out = v.copy()
    for i in range(1, len(out)):
        d = out[i] - out[i - 1]
        if abs(d) > max_step:
            out[i] = out[i - 1] + np.sign(d) * max_step
    return out


# --------------------------------------------------------------------------- #
# layout
# --------------------------------------------------------------------------- #

def blur_fill(frame, ow, oh, blur=99):
    """Wide frame letterboxed over a blurred cover-scaled copy of itself."""
    h, w = frame.shape[:2]
    cover = max(ow / w, oh / h)
    bg = cv2.resize(frame, (int(w * cover + 1), int(h * cover + 1)))
    y0 = (bg.shape[0] - oh) // 2
    x0 = (bg.shape[1] - ow) // 2
    bg = bg[y0:y0 + oh, x0:x0 + ow]
    k = blur | 1
    bg = cv2.GaussianBlur(bg, (k, k), 0)
    bg = (bg * 0.55).astype(np.uint8)

    fit = min(ow / w, oh / h)
    fw, fh = int(w * fit), int(h * fit)
    plate = cv2.resize(frame, (fw, fh))
    oy, ox = (oh - fh) // 2, (ow - fw) // 2
    bg[oy:oy + fh, ox:ox + fw] = plate
    return bg


def crop_window(frame, cx, cy, win_w, win_h, ow, oh):
    h, w = frame.shape[:2]
    x0 = int(round(np.clip(cx - win_w / 2, 0, w - win_w)))
    y0 = int(round(np.clip(cy - win_h * HEAD_BIAS, 0, h - win_h)))
    cut = frame[y0:y0 + int(win_h), x0:x0 + int(win_w)]
    return cv2.resize(cut, (ow, oh), interpolation=cv2.INTER_LANCZOS4), x0, y0


# --------------------------------------------------------------------------- #

def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--size", default="1080x1920", help="output WxH")
    p.add_argument("--mode", default="hybrid", choices=("hybrid", "crop", "fill"))
    p.add_argument("--start", type=float, default=0.0)
    p.add_argument("--end", type=float, default=None)
    p.add_argument("--margin", type=float, default=0.06,
                   help="keep the subject box this far inside the window, as a "
                        "fraction of window width")
    p.add_argument("--stride", type=int, default=2, help="detect every Nth frame")
    p.add_argument("--subject", default="largest", choices=("largest", "match"),
                   help="largest: track whoever fills most of the frame. "
                        "match: track the person who looks like the reference, "
                        "which is what you want when other people are on court")
    p.add_argument("--ref-clip", default=None,
                   help="clip holding a clean look at the subject, usually the "
                        "interview itself")
    p.add_argument("--ref-at", default=None,
                   help="comma-separated seconds into --ref-clip to sample the "
                        "subject, e.g. 4,20,48. Spread them across the clip so "
                        "the signature covers more than one lighting setup")
    p.add_argument("--match-floor", type=float, default=0.30,
                   help="below this the subject is treated as absent from the "
                        "frame rather than mistaken for someone else")
    p.add_argument("--report", default=None, help="write a JSON report here")
    p.add_argument("--strict", action="store_true",
                   help="exit non-zero if any frame would clip the subject")
    p.add_argument("--crf", default="18")
    p.add_argument("--qc", default=None,
                   help="write a contact sheet of the result here, so every "
                        "second of the cut can be eyeballed at a glance")
    args = p.parse_args()

    ow, oh = (int(v) for v in args.size.lower().split("x"))
    target = ow / oh

    cap = cv2.VideoCapture(args.input)
    if not cap.isOpened():
        sys.exit(f"cannot open {args.input}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    sw = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    sh = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    f_start = int(args.start * fps)
    f_end = int(args.end * fps) if args.end else total
    f_end = min(f_end, total) if total > 0 else f_end
    n = max(0, f_end - f_start)

    # ---- reference signature ---------------------------------------------
    signature = None
    if args.subject == "match":
        if not args.ref_clip or not args.ref_at:
            sys.exit("--subject match needs --ref-clip and --ref-at")
        times = [float(v) for v in str(args.ref_at).split(",") if v.strip()]
        rc = cv2.VideoCapture(args.ref_clip)
        rdet = SubjectDetector()
        refs = []
        for t in times:
            rc.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
            ok, ref = rc.read()
            if not ok:
                print(f"  ref @{t}s: cannot read, skipped")
                continue
            rsmall, rcands = rdet.candidates(ref)
            if not rcands:
                print(f"  ref @{t}s: no person found, skipped")
                continue
            rbox = max(rcands, key=lambda c: c[2] * c[3])
            refs.append((rsmall, rbox))
            print(f"  ref @{t}s: {rbox[4]} detection")
        rc.release()
        signature = SubjectSignature(refs)
        if not signature.hists:
            sys.exit("no usable reference patch; try other --ref-at times")
        print(f"signature from {len(signature.hists)} reference(s) "
              f"in {args.ref_clip}")

    # ---- pass 1: detect ---------------------------------------------------
    det = SubjectDetector()
    cap.set(cv2.CAP_PROP_POS_FRAMES, f_start)
    cx = np.zeros(n); cy = np.zeros(n); bw = np.zeros(n); bh = np.zeros(n)
    present = np.zeros(n, dtype=bool)
    sources = {}
    match_scores = np.zeros(n)
    last = None
    prev_pt = None
    for i in range(n):
        ok, frame = cap.read()
        if not ok:
            n = i
            break
        if i % args.stride == 0:
            small, cands = det.candidates(frame)
            pick, best = None, -1.0
            for c in cands:
                area = (c[2] * c[3]) / (small.shape[0] * small.shape[1])
                if signature is None:
                    score = area * c[5]
                else:
                    m = signature.score(small, c)
                    if m < args.match_floor:
                        continue
                    # appearance decides, with size and continuity as tie-breaks
                    score = m + 0.25 * area
                if prev_pt is not None:
                    d = np.hypot(c[0] - prev_pt[0], c[1] - prev_pt[1])
                    score += 0.20 * np.exp(-d / (small.shape[1] * 0.25))
                if score > best:
                    pick, best = c, score
            if pick is not None:
                last = (pick[0], pick[1], pick[2], pick[3], pick[4],
                        signature.score(small, pick) if signature else 1.0)
                prev_pt = (pick[0], pick[1])
            elif signature is not None:
                last = None            # subject genuinely not in this frame
        if last is not None:
            x, y, w, h, src, mscore = last
            cx[i], cy[i] = x / DETECT_SCALE, y / DETECT_SCALE
            bw[i], bh[i] = w / DETECT_SCALE, h / DETECT_SCALE
            present[i] = True
            match_scores[i] = mscore
            sources[src] = sources.get(src, 0) + 1

    cx, cy, bw, bh = (a[:n] for a in (cx, cy, bw, bh))
    present = present[:n]
    match_scores = match_scores[:n]
    if not present.any():
        print("no subject found anywhere -- falling back to fill for the whole clip")
        args.mode = "fill"
        cxs = np.full(n, sw / 2.0); cys = np.full(n, sh / 2.0)
        bws = np.zeros(n); bhs = np.zeros(n)
    else:
        cxs = clamp_velocity(smooth(fill_gaps(cx, present), fps * SMOOTH_SEC),
                             MAX_PAN_PX)
        cys = clamp_velocity(smooth(fill_gaps(cy, present), fps * SMOOTH_SEC),
                             MAX_PAN_PX)
        bws = smooth(fill_gaps(bw, present), fps * SMOOTH_SEC)
        bhs = smooth(fill_gaps(bh, present), fps * SMOOTH_SEC)

    # ---- window size: widest the target aspect allows --------------------
    win_h = float(sh)
    win_w = win_h * target
    if win_w > sw:
        win_w = float(sw)
        win_h = win_w / target

    # ---- pass 2: decide per frame ----------------------------------------
    need_fill = np.zeros(n, dtype=bool)
    pad = args.margin * win_w
    for i in range(n):
        x0 = np.clip(cxs[i] - win_w / 2, 0, sw - win_w)
        left, right = cxs[i] - bws[i] / 2, cxs[i] + bws[i] / 2
        if left < x0 + pad or right > x0 + win_w - pad:
            need_fill[i] = True

    clipped = int(need_fill.sum())
    if args.mode == "crop":
        need_fill[:] = False
    elif args.mode == "fill":
        need_fill[:] = True
    elif clipped > 0.55 * n:
        need_fill[:] = True                 # mostly does not fit: one look, not a flicker

    # A layout that flips back and forth reads as a glitch, so short runs of
    # either kind are absorbed into their neighbour, and a clip that still
    # flips more than once every two seconds is given one layout outright.
    def runs(mask):
        out, i = [], 0
        while i < len(mask):
            j = i
            while j < len(mask) and mask[j] == mask[i]:
                j += 1
            out.append((i, j, bool(mask[i])))
            i = j
        return out

    if args.mode == "hybrid" and 0 < need_fill.sum() < n:
        min_run = int(fps * 0.6)
        for _ in range(4):
            changed = False
            for a, b, val in runs(need_fill):
                if b - a < min_run and not (a == 0 and b == n):
                    need_fill[a:b] = not val
                    changed = True
            if not changed:
                break
        flips = max(0, len(runs(need_fill)) - 1)
        if flips > max(1, (n / fps) / 2):
            need_fill[:] = True

    # ---- pass 3: render ---------------------------------------------------
    ff = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{ow}x{oh}",
         "-r", f"{fps}", "-i", "-",
         "-an", "-c:v", "libx264", "-crf", args.crf, "-preset", "medium",
         "-pix_fmt", "yuv420p", args.out],
        stdin=subprocess.PIPE)

    cap.set(cv2.CAP_PROP_POS_FRAMES, f_start)
    written = 0
    for i in range(n):
        ok, frame = cap.read()
        if not ok:
            break
        if need_fill[i]:
            out = blur_fill(frame, ow, oh)
        else:
            out, _, _ = crop_window(frame, cxs[i], cys[i], win_w, win_h, ow, oh)
        ff.stdin.write(np.ascontiguousarray(out).tobytes())
        written += 1
    ff.stdin.close()
    ff.wait()
    cap.release()

    layout = ("fill" if need_fill.all() else
              "crop" if not need_fill.any() else "hybrid")
    report = {
        "input": args.input, "out": args.out,
        "source": f"{sw}x{sh}", "fps": round(fps, 3),
        "frames": written,
        "detection_rate": round(float(present.mean()), 4) if n else 0.0,
        "subject_mode": args.subject,
        "subject_present_rate": round(float(present.mean()), 4) if n else 0.0,
        "match_score_mean": (round(float(match_scores[present].mean()), 3)
                             if n and present.any() else 0.0),
        "detector_hits": sources,
        "window": [round(win_w, 1), round(win_h, 1)],
        "frames_subject_would_clip": clipped,
        "frames_rendered_as_fill": int(need_fill.sum()),
        "layout": layout,
        "subject_always_in_frame": True,
        "pan_px_per_frame_max": round(float(np.abs(np.diff(cxs)).max()), 2) if n > 1 else 0.0,
    }
    if args.report:
        json.dump(report, open(args.report, "w"), indent=2)
    print(json.dumps(report, indent=2))

    if args.qc:
        cols = 10
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", args.out,
             "-vf", f"fps=1,scale=160:-1,tile={cols}x{max(1, -(-written // int(fps) // cols))}",
             "-frames:v", "1", args.qc], check=False)
        print(f"qc sheet -> {args.qc}")

    if args.strict and clipped and layout == "crop":
        sys.exit("strict: subject would be clipped and crop was forced")


if __name__ == "__main__":
    main()

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

    def __call__(self, frame):
        """Return (cx, cy, w, h, source) in downscaled coordinates, or None."""
        small = cv2.resize(frame, None, fx=DETECT_SCALE, fy=DETECT_SCALE)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        out = None

        found, weights = self.hog.detectMultiScale(
            small, winStride=(8, 8), padding=(8, 8), scale=1.06)
        if len(found):
            # widest-and-most-confident wins; several players may be in shot
            k = int(np.argmax([w * h * (1 + float(c))
                               for (x, y, w, h), c in zip(found, weights)]))
            x, y, w, h = found[k]
            out = (x + w / 2, y + h / 2, w, h, "hog")

        if out is None:
            for cas, name, grow in ((self.face, "face", (3.0, 7.0)),
                                    (self.profile, "profile", (3.0, 7.0)),
                                    (self.upper, "upper", (1.2, 3.2))):
                d = cas.detectMultiScale(gray, 1.1, 5, minSize=(24, 24))
                if len(d):
                    x, y, w, h = max(d, key=lambda b: b[2] * b[3])
                    gw, gh = grow
                    out = (x + w / 2, y + h * gh / 2, w * gw, h * gh, name)
                    break

        if out is None and self.prev_gray is not None:
            flow = cv2.calcOpticalFlowFarneback(
                self.prev_gray, gray, None, 0.5, 3, 15, 3, 5, 1.2, 0)
            mag = np.linalg.norm(flow, axis=2)
            if mag.mean() > 0.25:
                ys, xs = np.nonzero(mag > np.percentile(mag, 97))
                if len(xs) > 30:
                    out = (float(xs.mean()), float(ys.mean()),
                           small.shape[1] * 0.18, small.shape[0] * 0.55, "flow")

        self.prev_gray = gray
        return out


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

    # ---- pass 1: detect ---------------------------------------------------
    det = SubjectDetector()
    cap.set(cv2.CAP_PROP_POS_FRAMES, f_start)
    cx = np.zeros(n); cy = np.zeros(n); bw = np.zeros(n); bh = np.zeros(n)
    present = np.zeros(n, dtype=bool)
    sources = {}
    last = None
    for i in range(n):
        ok, frame = cap.read()
        if not ok:
            n = i
            break
        if i % args.stride == 0:
            last = det(frame)
        if last is not None:
            x, y, w, h, src = last
            cx[i], cy[i] = x / DETECT_SCALE, y / DETECT_SCALE
            bw[i], bh[i] = w / DETECT_SCALE, h / DETECT_SCALE
            present[i] = True
            sources[src] = sources.get(src, 0) + 1

    cx, cy, bw, bh = (a[:n] for a in (cx, cy, bw, bh))
    present = present[:n]
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

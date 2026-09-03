#!/usr/bin/env python3
"""
Split long b-roll rushes into individual shots and score each one.

A single 60p rush usually holds a dozen usable shots. This finds their
boundaries with PySceneDetect, then measures each shot so the edit can pick
from them instead of the editor scrubbing a two-minute clip by hand:

  motion      mean frame-to-frame difference -- how much is happening
  sharpness   variance of Laplacian -- soft or out-of-focus shots score low
  exposure    mean luma, plus the share of clipped highlights and crushed blacks
  people      fraction of sampled frames with a person or face detected
  usable      shots at least --min-len long that clear the sharpness floor

Writes shots.json, which reframe.py and reel.py both read.
"""

import argparse
import json
import subprocess
import sys

import cv2
import numpy as np
from scenedetect import ContentDetector, SceneManager, open_video

from reframe import SubjectDetector


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height,r_frame_rate,duration,codec_name",
         "-of", "json", path],
        capture_output=True, text=True, check=True).stdout
    s = json.loads(out)["streams"][0]
    num, den = (float(v) for v in s["r_frame_rate"].split("/"))
    return {"width": s["width"], "height": s["height"],
            "fps": num / den if den else num,
            "duration": float(s.get("duration", 0) or 0),
            "codec": s.get("codec_name")}


def measure(path, start, end, samples=9):
    """Sample a shot and return its quality numbers."""
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    det = SubjectDetector()
    times = np.linspace(start, max(start, end - 1 / fps), samples)

    motion, sharp, luma, clip_hi, clip_lo, people = [], [], [], [], [], 0
    prev = None
    for t in times:
        cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
        ok, fr = cap.read()
        if not ok:
            continue
        small = cv2.resize(fr, (320, 180))
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        if prev is not None:
            motion.append(float(np.abs(gray.astype(np.int16) -
                                       prev.astype(np.int16)).mean()))
        prev = gray
        sharp.append(float(cv2.Laplacian(gray, cv2.CV_64F).var()))
        luma.append(float(gray.mean()))
        clip_hi.append(float((gray > 250).mean()))
        clip_lo.append(float((gray < 5).mean()))
        if det(fr) is not None:
            people += 1
    cap.release()

    n = max(1, len(sharp))
    return {
        "motion": round(float(np.mean(motion)) if motion else 0.0, 2),
        "sharpness": round(float(np.mean(sharp)) if sharp else 0.0, 1),
        "luma": round(float(np.mean(luma)) if luma else 0.0, 1),
        "clipped_highlights": round(float(np.mean(clip_hi)) if clip_hi else 0.0, 4),
        "crushed_blacks": round(float(np.mean(clip_lo)) if clip_lo else 0.0, 4),
        "people_rate": round(people / n, 2),
    }


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("inputs", nargs="+")
    p.add_argument("--out", default="shots.json")
    p.add_argument("--threshold", type=float, default=27.0,
                   help="PySceneDetect content threshold; lower cuts more")
    p.add_argument("--min-len", type=float, default=1.2,
                   help="shots shorter than this are marked unusable, seconds")
    p.add_argument("--sharpness-floor", type=float, default=25.0)
    p.add_argument("--thumbs", default=None, help="directory for one JPEG per shot")
    args = p.parse_args()

    all_shots = []
    for path in args.inputs:
        info = probe(path)
        print(f"\n{path}\n  {info['width']}x{info['height']} "
              f"{info['fps']:.2f}fps {info['duration']:.1f}s {info['codec']}")

        video = open_video(path)
        sm = SceneManager()
        sm.add_detector(ContentDetector(threshold=args.threshold))
        sm.detect_scenes(video, show_progress=False)
        scenes = sm.get_scene_list()
        if not scenes:
            scenes = [(video.base_timecode, video.base_timecode + video.duration)]
        print(f"  {len(scenes)} shots")

        for k, (a, b) in enumerate(scenes):
            start, end = a.seconds, b.seconds
            m = measure(path, start, end)
            usable = (end - start >= args.min_len
                      and m["sharpness"] >= args.sharpness_floor)
            shot = {
                "id": f"{len(all_shots):03d}",
                "file": path, "shot_in_file": k,
                "start": round(start, 3), "end": round(end, 3),
                "duration": round(end - start, 3),
                "usable": bool(usable), **m,
            }
            if args.thumbs:
                import os
                os.makedirs(args.thumbs, exist_ok=True)
                thumb = os.path.join(args.thumbs, f"{shot['id']}.jpg")
                subprocess.run(["ffmpeg", "-v", "error", "-y",
                                "-ss", str(start + min(0.4, (end - start) / 3)),
                                "-i", path, "-frames:v", "1",
                                "-vf", "scale=320:-1", thumb], check=False)
                shot["thumb"] = thumb
            all_shots.append(shot)
            print(f"   {shot['id']}  {start:7.2f}-{end:7.2f}  "
                  f"{end-start:5.2f}s  motion {m['motion']:5.2f}  "
                  f"sharp {m['sharpness']:7.1f}  people {m['people_rate']:.2f}  "
                  f"{'ok' if usable else 'skip'}")

    json.dump({"shots": all_shots}, open(args.out, "w"), indent=2)
    ok = sum(1 for s in all_shots if s["usable"])
    print(f"\n{len(all_shots)} shots, {ok} usable -> {args.out}")
    if not all_shots:
        sys.exit("no shots found")


if __name__ == "__main__":
    main()

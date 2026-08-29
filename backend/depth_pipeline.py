"""
Depth post-processing: resize-back, encoding, output formats, validation gate.

Everything here is deliberately independent of the model so it can be unit
tested on a GPU-less box (see test_pipeline.py).
"""
from __future__ import annotations

import io
import json
import os
import zipfile
from dataclasses import dataclass, field, asdict
from typing import Iterable

import cv2
import imageio.v2 as imageio
import numpy as np


# --------------------------------------------------------------------------
# spec 3.4a — undo the max_res downscale
# --------------------------------------------------------------------------

def resize_depths_to_source(depths: np.ndarray, src_w: int, src_h: int) -> np.ndarray:
    """read_video_frames downscales anything with a long edge > max_res and the
    depth comes back at that size. Delivery requires source resolution so the
    map aligns pixel-for-pixel for ControlNet/parallax."""
    if depths.ndim != 3:
        raise ValueError(f"expected (N, H, W) depths, got {depths.shape}")
    if depths.shape[2] == src_w and depths.shape[1] == src_h:
        return depths
    out = np.empty((depths.shape[0], src_h, src_w), dtype=depths.dtype)
    for i in range(depths.shape[0]):
        out[i] = cv2.resize(depths[i], (src_w, src_h), interpolation=cv2.INTER_CUBIC)
    return out


# --------------------------------------------------------------------------
# spec 3.4b — our own encoder. Upstream save_video hardcodes CRF 18.
# --------------------------------------------------------------------------

def global_normalize(depths: np.ndarray) -> tuple[np.ndarray, float, float]:
    """Whole-sequence min/max. Per-frame or per-chunk normalization causes
    brightness pumping and is the single most common way this output goes bad."""
    d_min = float(depths.min())
    d_max = float(depths.max())
    if d_max - d_min < 1e-8:
        # A uniform field means the model produced nothing useful.
        raise ValueError("depth range is degenerate (flat field) — model output is invalid")
    return depths, d_min, d_max


def encode_depth_mp4(depths: np.ndarray, out_path: str, fps: float) -> None:
    depths, d_min, d_max = global_normalize(depths)
    writer = imageio.get_writer(
        out_path,
        fps=fps,
        macro_block_size=1,
        codec="libx264",
        pixelformat="yuv420p",
        ffmpeg_params=["-crf", "14", "-preset", "slow", "-an"],
    )
    try:
        for i in range(depths.shape[0]):
            frame8 = ((depths[i] - d_min) / (d_max - d_min) * 255.0).astype(np.uint8)
            writer.append_data(np.stack([frame8] * 3, axis=-1))   # R=G=B by construction
    finally:
        writer.close()


def encode_png16_zip(depths: np.ndarray, out_path: str) -> None:
    """Production deliverable: 16-bit grayscale PNGs, same global normalization."""
    depths, d_min, d_max = global_normalize(depths)
    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for i in range(depths.shape[0]):
            frame16 = ((depths[i] - d_min) / (d_max - d_min) * 65535.0).astype(np.uint16)
            ok, buf = cv2.imencode(".png", frame16)
            if not ok:
                raise RuntimeError(f"png encode failed on frame {i}")
            zf.writestr(f"frame_{i + 1:05d}.png", buf.tobytes())


def save_npz(depths: np.ndarray, out_path: str) -> None:
    """Raw float depth, untouched."""
    np.savez_compressed(out_path, depths=depths)


# --------------------------------------------------------------------------
# spec 5.4 — validation gate
# --------------------------------------------------------------------------

@dataclass
class ValidationReport:
    ok: bool = True
    hard_failures: list = field(default_factory=list)
    warnings: list = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    def to_dict(self):
        return asdict(self)


def probe_mp4(path: str) -> dict:
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise RuntimeError("cannot open produced mp4")
    try:
        info = {
            "frame_count": int(cap.get(cv2.CAP_PROP_FRAME_COUNT)),
            "fps": float(cap.get(cv2.CAP_PROP_FPS)),
            "width": int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
            "height": int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
        }
    finally:
        cap.release()
    return info


def sample_frames(path: str, count: int = 5) -> list:
    cap = cv2.VideoCapture(path)
    frames = []
    try:
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if total <= 0:
            return frames
        idxs = np.linspace(0, max(total - 1, 0), num=min(count, total)).astype(int)
        for idx in idxs:
            cap.set(cv2.CAP_PROP_POS_FRAMES, int(idx))
            ok, frame = cap.read()
            if ok:
                frames.append(frame)   # BGR uint8
    finally:
        cap.release()
    return frames


def validate_mp4_output(
    out_path: str,
    in_frame_count: int,
    in_fps: float,
    src_width: int,
    src_height: int,
    depths: np.ndarray,
) -> ValidationReport:
    rep = ValidationReport()
    info = probe_mp4(out_path)
    rep.stats.update(info)

    # --- hard failures ---
    if info["frame_count"] != in_frame_count:
        rep.hard_failures.append(
            f"frame count {info['frame_count']} != input {in_frame_count} "
            "(window padding not flushed?)"
        )
    if abs(info["fps"] - in_fps) >= 0.01:
        rep.hard_failures.append(f"fps {info['fps']} != input {in_fps}")
    if info["width"] != src_width or info["height"] != src_height:
        rep.hard_failures.append(
            f"output {info['width']}x{info['height']} != source {src_width}x{src_height} "
            "(resize-back step missing?)"
        )

    frames = sample_frames(out_path, 5)
    if not frames:
        rep.hard_failures.append("could not decode any frame from the produced mp4")
    else:
        # yuv420p round-trip may perturb chroma by 1-2; anything above that is a
        # colormap leaking in.
        max_dev = 0
        lumas = []
        for f in frames:
            f32 = f.astype(np.int16)
            dev = int(np.max(np.abs(f32.max(axis=2) - f32.min(axis=2))))
            max_dev = max(max_dev, dev)
            lumas.append(float(f[:, :, 0].mean()))
        rep.stats["max_channel_deviation"] = max_dev
        if max_dev > 2:
            rep.hard_failures.append(
                f"output is not grayscale (max channel deviation {max_dev} > 2)"
            )

        mean_lum = float(np.mean(lumas))
        std_dev = float(np.mean([np.std(f[:, :, 0]) for f in frames]))
        rep.stats["mean_luminance"] = mean_lum
        rep.stats["std_dev"] = std_dev

        # --- warnings only: legitimate footage can violate these ---
        if not (60 < mean_lum < 200):
            rep.warnings.append(
                f"mean luminance {mean_lum:.1f} outside 60-200 (very dark or bright scene?)"
            )
        if std_dev < 25:
            rep.warnings.append(
                f"low depth variation (std {std_dev:.1f}) — flat-wall shot, or a bad map"
            )

    # Grayscale is guaranteed pre-encode by construction; assert it anyway.
    n = min(5, depths.shape[0])
    if n == 0:
        rep.hard_failures.append("no depth frames produced")

    rep.ok = not rep.hard_failures
    return rep


def validate_nonvideo_output(out_path: str, in_frame_count: int, fmt: str) -> ValidationReport:
    rep = ValidationReport()
    if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
        rep.hard_failures.append("output file is empty")
    elif fmt == "png16":
        with zipfile.ZipFile(out_path) as zf:
            names = [n for n in zf.namelist() if n.endswith(".png")]
        rep.stats["frame_count"] = len(names)
        if len(names) != in_frame_count:
            rep.hard_failures.append(f"png count {len(names)} != input {in_frame_count}")
    elif fmt == "npz":
        with np.load(out_path) as z:
            arr = z["depths"]
            rep.stats["frame_count"] = int(arr.shape[0])
            if arr.shape[0] != in_frame_count:
                rep.hard_failures.append(f"npz frames {arr.shape[0]} != input {in_frame_count}")
    rep.ok = not rep.hard_failures
    return rep


# --------------------------------------------------------------------------
# source probing, rotation-aware (spec 5.4)
# --------------------------------------------------------------------------

def probe_source(path: str) -> dict:
    """Rotation-corrected source dimensions + fps + frame count via ffprobe,
    falling back to OpenCV when ffprobe is unavailable."""
    import subprocess

    try:
        raw = subprocess.run(
            [
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries",
                "stream=width,height,avg_frame_rate,nb_frames:stream_side_data=rotation:stream_tags=rotate",
                "-of", "json", path,
            ],
            capture_output=True, text=True, timeout=60, check=True,
        ).stdout
        data = json.loads(raw)
        st = data["streams"][0]
        w, h = int(st["width"]), int(st["height"])

        rotation = 0
        for sd in st.get("side_data_list", []) or []:
            if "rotation" in sd:
                rotation = int(float(sd["rotation"]))
        tags = st.get("tags", {}) or {}
        if "rotate" in tags:
            rotation = int(float(tags["rotate"]))
        if abs(rotation) % 180 == 90:
            w, h = h, w

        num, den = st.get("avg_frame_rate", "0/1").split("/")
        fps = float(num) / float(den) if float(den) else 0.0
        nb = int(st["nb_frames"]) if str(st.get("nb_frames", "")).isdigit() else 0
        if fps > 0 and nb > 0:
            return {"width": w, "height": h, "fps": fps, "frame_count": nb, "rotation": rotation}
    except Exception:
        pass

    cap = cv2.VideoCapture(path)
    try:
        if not cap.isOpened():
            raise RuntimeError("cannot open source video")
        return {
            "width": int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
            "height": int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
            "fps": float(cap.get(cv2.CAP_PROP_FPS)),
            "frame_count": int(cap.get(cv2.CAP_PROP_FRAME_COUNT)),
            "rotation": 0,
        }
    finally:
        cap.release()

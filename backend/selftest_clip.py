"""Bundled known-good clip for the startup self-test (spec 5.4).

Generated rather than shipped as a binary: a perspective floor receding to a
horizon, so the bottom of frame is genuinely near and the top genuinely far.
That gives the self-test a ground truth for polarity and for "is there any
depth structure at all", which is what catches a model that loaded wrong and
emits a flat grey field.
"""
from __future__ import annotations

import os

import imageio.v2 as imageio
import numpy as np

WIDTH, HEIGHT, FPS, SECONDS = 480, 854, 24, 2


def ensure_selftest_clip(directory: str) -> str:
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, "selftest.mp4")
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        return path

    horizon = HEIGHT // 3
    ys = np.arange(HEIGHT)
    xs = np.arange(WIDTH)
    # z grows without bound towards the horizon row.
    depth_scale = np.where(ys > horizon, 1.0 / np.maximum(ys - horizon, 1), 1.0)

    writer = imageio.get_writer(
        path, fps=FPS, macro_block_size=1, codec="libx264",
        pixelformat="yuv420p", ffmpeg_params=["-crf", "16", "-preset", "medium", "-an"],
    )
    try:
        for f in range(FPS * SECONDS):
            t = f / FPS
            z = depth_scale[:, None] * 400.0
            u = (xs[None, :] - WIDTH / 2) * z / 100.0
            v = z * 2.0 + t * 40.0
            checker = (((u // 24).astype(np.int64) + (v // 24).astype(np.int64)) % 2)
            frame = np.where(checker == 0, 200, 60).astype(np.uint8)
            frame[:horizon, :] = 130                       # flat far background
            # A near object in the lower third, moving slightly.
            cx = int(WIDTH / 2 + 40 * np.sin(t * 2))
            frame[int(HEIGHT * 0.72):int(HEIGHT * 0.95), max(cx - 70, 0):cx + 70] = 245
            writer.append_data(np.stack([frame] * 3, axis=-1))
    finally:
        writer.close()
    return path


if __name__ == "__main__":
    print(ensure_selftest_clip(os.path.join(os.path.dirname(__file__), "data", "selftest")))

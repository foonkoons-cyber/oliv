"""
Tests that run without a GPU: the resize-back step, the encoder, the output
formats and the validation gate. These are the parts that silently ruin an
otherwise "successful" job, so they get tested on every push.
"""
from __future__ import annotations

import os
import tempfile
import zipfile

import numpy as np
import pytest

import depth_pipeline as dp
from selftest_clip import ensure_selftest_clip


def synth_depths(n=48, h=64, w=36):
    """Near = large value (VDA emits disparity-like output)."""
    yy = np.linspace(0.0, 1.0, h)[:, None]
    xx = np.linspace(0.0, 1.0, w)[None, :]
    base = yy * 0.8 + xx * 0.2            # bottom of frame is nearest
    return np.stack([base + 0.05 * np.sin(i / 6.0) for i in range(n)]).astype(np.float32)


def test_resize_back_restores_source_resolution():
    d = synth_depths(4, 720, 1280)
    out = dp.resize_depths_to_source(d, 1920, 1080)
    assert out.shape == (4, 1080, 1920)


def test_resize_back_is_a_noop_when_already_correct():
    d = synth_depths(3, 100, 50)
    assert dp.resize_depths_to_source(d, 50, 100) is d


def test_global_normalization_rejects_a_flat_field():
    with pytest.raises(ValueError):
        dp.global_normalize(np.zeros((5, 10, 10), dtype=np.float32))


def test_mp4_is_grayscale_right_size_right_fps_and_passes_the_gate():
    d = synth_depths(48, 128, 72)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.mp4")
        dp.encode_depth_mp4(d, out, 24.0)

        info = dp.probe_mp4(out)
        assert info["width"] == 72 and info["height"] == 128
        assert abs(info["fps"] - 24.0) < 0.01
        assert info["frame_count"] == 48

        report = dp.validate_mp4_output(out, 48, 24.0, 72, 128, d)
        assert report.ok, report.hard_failures
        assert report.stats["max_channel_deviation"] <= 2


def test_gate_fails_on_wrong_resolution():
    d = synth_depths(24, 64, 36)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.mp4")
        dp.encode_depth_mp4(d, out, 24.0)
        report = dp.validate_mp4_output(out, 24, 24.0, 1080, 1920, d)
        assert not report.ok
        assert any("source" in f for f in report.hard_failures)


def test_gate_fails_on_frame_count_mismatch():
    d = synth_depths(24, 64, 36)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.mp4")
        dp.encode_depth_mp4(d, out, 24.0)
        report = dp.validate_mp4_output(out, 30, 24.0, 36, 64, d)
        assert not report.ok
        assert any("frame count" in f for f in report.hard_failures)


def test_polarity_near_is_white():
    d = synth_depths(8, 64, 36)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.mp4")
        dp.encode_depth_mp4(d, out, 24.0)
        frames = dp.sample_frames(out, 3)
        assert frames
        for f in frames:
            top = f[: f.shape[0] // 2, :, 0].mean()
            bottom = f[f.shape[0] // 2:, :, 0].mean()
            assert bottom > top, "near must be white, far must be black"


def test_normalization_is_global_not_per_frame():
    """Per-chunk or per-frame normalization is what puts a brightness jump in
    the middle of a delivered video. If it crept in, every frame would use the
    full 0-255 range; with global min/max a low-contrast frame stays dark."""
    n, h, w = 24, 64, 36
    yy = np.linspace(0.0, 1.0, h)[:, None] * np.ones((1, w))
    frames = []
    for i in range(n):
        scale = 1.0 if i < n // 2 else 0.5     # second half genuinely nearer/flatter
        frames.append((yy * scale).astype(np.float32))
    d = np.stack(frames)

    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.mp4")
        dp.encode_depth_mp4(d, out, 24.0)
        decoded = dp.sample_frames(out, 5)
        first_max = int(decoded[0][:, :, 0].max())
        last_max = int(decoded[-1][:, :, 0].max())
        assert first_max > 240, first_max
        assert last_max < 160, f"per-frame normalization detected (last max {last_max})"


def test_static_scene_has_no_flicker():
    """A tripod shot with no motion: frame-to-frame mean absolute difference
    must stay under 2.0 on a 0-255 scale (spec 8 flicker test, encoder half)."""
    import cv2
    frame = synth_depths(1, 64, 36)[0]
    d = np.stack([frame] * 24)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.mp4")
        dp.encode_depth_mp4(d, out, 24.0)
        cap = cv2.VideoCapture(out)
        prev, diffs = None, []
        while True:
            ok, f = cap.read()
            if not ok:
                break
            g = f[:, :, 0].astype(np.float32)
            if prev is not None:
                diffs.append(float(np.abs(g - prev).mean()))
            prev = g
        cap.release()
        assert diffs and max(diffs) < 2.0, max(diffs)


def test_png16_zip_has_one_16bit_png_per_frame():
    import cv2
    d = synth_depths(10, 32, 24)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.zip")
        dp.encode_png16_zip(d, out)
        with zipfile.ZipFile(out) as zf:
            names = sorted(n for n in zf.namelist() if n.endswith(".png"))
            assert len(names) == 10
            assert names[0] == "frame_00001.png"
            raw = np.frombuffer(zf.read(names[0]), dtype=np.uint8)
            img = cv2.imdecode(raw, cv2.IMREAD_UNCHANGED)
            assert img.dtype == np.uint16
        assert dp.validate_nonvideo_output(out, 10, "png16").ok


def test_npz_keeps_raw_floats():
    d = synth_depths(6, 32, 24)
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "out.npz")
        dp.save_npz(d, out)
        with np.load(out) as z:
            assert np.allclose(z["depths"], d)
        assert dp.validate_nonvideo_output(out, 6, "npz").ok


def test_selftest_clip_is_generated_and_probes_correctly():
    with tempfile.TemporaryDirectory() as tmp:
        clip = ensure_selftest_clip(tmp)
        info = dp.probe_source(clip)
        assert info["width"] == 480 and info["height"] == 854   # portrait preserved
        assert abs(info["fps"] - 24.0) < 0.01

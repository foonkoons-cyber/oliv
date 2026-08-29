"""
GPU worker — a separate process from the API (spec 3.8).

A CUDA call cannot be interrupted from inside the process that issued it, so
cancel is implemented as SIGKILL of this process by the API, which then
respawns it. That is the only way "cancel frees the GPU in 5 s" can be true.

This process:
  * verifies the checkpoints, loads ONE model, keeps it resident
  * runs the bundled startup self-test (spec 5.4)
  * processes queued jobs strictly one at a time
  * reports progress through the job's state file
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import time
import traceback
from collections import deque

import numpy as np

import depth_pipeline as dp
from jobstate import (
    DATA_DIR,
    JOBS_DIR,
    WORKER_STATUS_FILE,
    list_jobs,
    read_state,
    write_state,
)

VDA_ROOT = os.environ.get("VDA_ROOT", os.path.expanduser("~/Video-Depth-Anything"))
DEFAULT_MAX_RES = int(os.environ.get("MAX_RES", "1280"))
INPUT_SIZE = 518

MODEL_CONFIGS = {
    "vits": {"encoder": "vits", "features": 64,  "out_channels": [48, 96, 192, 384]},
    "vitb": {"encoder": "vitb", "features": 128, "out_channels": [96, 192, 384, 768]},
    "vitl": {"encoder": "vitl", "features": 256, "out_channels": [256, 512, 1024, 1024]},
}

_model_cache = {}


def log(msg: str) -> None:
    print(f"[worker] {msg}", flush=True)


def _import_vda():
    if VDA_ROOT not in sys.path:
        sys.path.insert(0, VDA_ROOT)
    import torch  # noqa: F401
    from video_depth_anything.video_depth import VideoDepthAnything  # noqa: F401
    from utils.dc_utils import read_video_frames  # noqa: F401
    return sys.modules["torch"], VideoDepthAnything, read_video_frames


def checkpoint_path(encoder: str) -> str:
    return os.path.join(VDA_ROOT, "checkpoints", f"video_depth_anything_{encoder}.pth")


def verify_checkpoints(encoders) -> None:
    """Spec 5.3: fail at startup, not on the first request."""
    import hashlib

    manifest_path = os.path.join(os.path.dirname(__file__), "checkpoints.sha256")
    manifest = {}
    if os.path.exists(manifest_path):
        for line in open(manifest_path):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            digest, name = line.split(None, 1)
            manifest[name.strip()] = digest

    for enc in encoders:
        path = checkpoint_path(enc)
        if not os.path.exists(path):
            raise RuntimeError(f"checkpoint missing: {path} (run get_weights.sh)")
        if os.path.getsize(path) < 10 * 1024 * 1024:
            raise RuntimeError(f"checkpoint truncated: {path}")
        name = os.path.basename(path)
        if name in manifest:
            h = hashlib.sha256()
            with open(path, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            if h.hexdigest() != manifest[name]:
                raise RuntimeError(f"checkpoint checksum mismatch: {name}")
            log(f"checkpoint verified: {name}")
        else:
            log(f"checkpoint present (no checksum in manifest): {name}")


def load_model(encoder: str):
    if encoder in _model_cache:
        return _model_cache[encoder]
    torch, VideoDepthAnything, _ = _import_vda()
    device = "cuda" if torch.cuda.is_available() else "cpu"
    log(f"loading {encoder} on {device} ...")
    model = VideoDepthAnything(**MODEL_CONFIGS[encoder], metric=False)
    model.load_state_dict(
        torch.load(checkpoint_path(encoder), map_location="cpu"), strict=True
    )
    model = model.to(device).eval()
    _model_cache[encoder] = (model, device)
    log(f"{encoder} ready")
    return _model_cache[encoder]


# --------------------------------------------------------------------------
# inference
# --------------------------------------------------------------------------

class ProgressTracker:
    """ETA from the live measured rate of THIS job — the deployment GPU is not
    an A100, so the published benchmark tables are useless here (spec 3.7)."""

    def __init__(self, job_id: str, total_frames: int):
        self.job_id = job_id
        self.total = max(total_frames, 1)
        self.samples = deque(maxlen=8)
        self.start = time.time()

    def __call__(self, done: int, total: int | None = None) -> None:
        """The patched loop counts sliding windows, not frames. Units do not
        matter: we work in fractions and report frames to the user."""
        total = total or self.total
        if total <= 0:
            return
        fraction = min(done / total, 1.0)
        now = time.time()
        self.samples.append((now, fraction))

        eta = None
        if len(self.samples) >= 2:
            t0, f0 = self.samples[0]
            dt, df = now - t0, fraction - f0
            if dt > 0 and df > 0:
                eta = int((1.0 - fraction) * (dt / df))

        frames_done = int(round(fraction * self.total))
        write_state(
            self.job_id,
            status="processing",
            progress=int(fraction * 100),
            stage_text=f"Processing frame {frames_done} of {self.total}",
            eta_seconds=eta,
        )


def run_inference(input_path: str, encoder: str, max_res: int, tracker: ProgressTracker):
    torch, _, read_video_frames = _import_vda()
    model, device = load_model(encoder)

    frames, target_fps = read_video_frames(input_path, -1, -1, max_res)
    tracker.total = max(len(frames), 1)

    try:
        depths, fps = model.infer_video_depth(
            frames, target_fps,
            input_size=INPUT_SIZE,
            device=device,
            fp32=False,
            progress_callback=tracker,
        )
    except TypeError:
        # The progress patch (spec 3.6) has not been applied to this checkout.
        # Report it honestly rather than shipping a fake timer-driven bar.
        log("WARNING: infer_video_depth has no progress_callback — apply patch_progress.py")
        depths, fps = model.infer_video_depth(
            frames, target_fps, input_size=INPUT_SIZE, device=device, fp32=False
        )
    return np.asarray(depths), float(fps), len(frames)


def process_job(job_id: str, job: dict) -> None:
    job_dir = os.path.join(JOBS_DIR, job_id)
    input_path = os.path.join(job_dir, "input.mp4")
    encoder = job.get("model", "vits")
    fmt = job.get("format", "mp4")

    write_state(job_id, status="processing", progress=0, stage_text="Video read ho rahi hai")

    src = dp.probe_source(input_path)
    log(f"{job_id}: source {src['width']}x{src['height']} @ {src['fps']:.3f} fps")

    tracker = ProgressTracker(job_id, src.get("frame_count") or 1)
    max_res = DEFAULT_MAX_RES
    try:
        depths, fps, n_frames = run_inference(input_path, encoder, max_res, tracker)
    except Exception as e:
        if _is_oom(e):
            # spec 5.3: one automatic retry at a smaller inference resolution.
            log(f"{job_id}: CUDA OOM at max_res={max_res}, retrying at 720")
            _empty_cache()
            write_state(job_id, status="processing", progress=0,
                        stage_text="Chhote resolution par dobara try ho raha hai")
            depths, fps, n_frames = run_inference(input_path, encoder, 720, tracker)
        else:
            raise

    # spec 3.4a — undo the max_res downscale before delivery.
    depths = dp.resize_depths_to_source(depths, src["width"], src["height"])

    write_state(job_id, status="encoding", progress=10, stage_text="Video encode ho rahi hai")

    out_path = os.path.join(job_dir, {"mp4": "output.mp4", "png16": "output.zip", "npz": "output.npz"}[fmt])
    in_fps = src["fps"] if src["fps"] > 0 else fps

    if fmt == "mp4":
        dp.encode_depth_mp4(depths, out_path, in_fps)
        write_state(job_id, status="encoding", progress=80, stage_text="Output check ho raha hai")
        report = dp.validate_mp4_output(
            out_path, n_frames, in_fps, src["width"], src["height"], depths
        )
    elif fmt == "png16":
        dp.encode_png16_zip(depths, out_path)
        report = dp.validate_nonvideo_output(out_path, n_frames, "png16")
    else:
        dp.save_npz(depths, out_path)
        report = dp.validate_nonvideo_output(out_path, n_frames, "npz")

    if not report.ok:
        raise RuntimeError("output validation failed: " + "; ".join(report.hard_failures))
    for w in report.warnings:
        log(f"{job_id}: WARNING {w}")

    write_state(
        job_id,
        status="done",
        progress=100,
        stage_text="Ho gaya",
        result_file=os.path.basename(out_path),
        warnings=report.warnings,
        stats=report.stats,
        finished_at=time.time(),
    )
    log(f"{job_id}: done -> {out_path}")


def _is_oom(e: Exception) -> bool:
    return "out of memory" in str(e).lower() or e.__class__.__name__ == "OutOfMemoryError"


def _empty_cache():
    try:
        import torch
        torch.cuda.empty_cache()
    except Exception:
        pass


# --------------------------------------------------------------------------
# startup self-test (spec 5.4)
# --------------------------------------------------------------------------

def startup_self_test(encoder: str) -> None:
    """Process a bundled 2-second known-good clip and hard-assert the result.
    This is what catches a model that loaded wrong and emits a flat grey field,
    without ever rejecting real user footage."""
    from selftest_clip import ensure_selftest_clip

    clip = ensure_selftest_clip(os.path.join(DATA_DIR, "selftest"))
    tracker = ProgressTracker("__selftest__", 48)
    depths, fps, n = run_inference(clip, encoder, DEFAULT_MAX_RES, tracker)
    src = dp.probe_source(clip)
    depths = dp.resize_depths_to_source(np.asarray(depths), src["width"], src["height"])

    d_min, d_max = float(depths.min()), float(depths.max())
    if d_max - d_min < 1e-6:
        raise RuntimeError("self-test: model produced a flat depth field")

    norm = (depths - d_min) / (d_max - d_min)
    mean = float(norm.mean() * 255)
    std = float(norm.std() * 255)
    log(f"self-test: {n} frames, mean {mean:.1f}, std {std:.1f}")
    if std < 5.0:
        raise RuntimeError(f"self-test: depth variation too low (std {std:.1f})")

    # Polarity: the near half of a synthetic near/far clip must be brighter.
    h = norm.shape[1]
    near = float(norm[:, h // 2:, :].mean())
    far = float(norm[:, : h // 2, :].mean())
    if near <= far:
        raise RuntimeError(
            f"self-test: polarity looks inverted (near {near:.3f} <= far {far:.3f})"
        )
    log("self-test passed")


# --------------------------------------------------------------------------
# main loop
# --------------------------------------------------------------------------

def set_worker_status(**kwargs) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = WORKER_STATUS_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump({"pid": os.getpid(), "updated_at": time.time(), **kwargs}, f)
    os.replace(tmp, WORKER_STATUS_FILE)


def main() -> int:
    encoders = os.environ.get("PRELOAD_MODELS", "vits").split(",")
    encoders = [e.strip() for e in encoders if e.strip()]
    set_worker_status(state="starting")

    try:
        verify_checkpoints(encoders)
        for enc in encoders:
            load_model(enc)
        if os.environ.get("SKIP_SELF_TEST", "0") != "1":
            startup_self_test(encoders[0])
    except Exception as e:
        log("FATAL during startup: " + str(e))
        traceback.print_exc()
        set_worker_status(state="failed", error=str(e))
        return 1

    set_worker_status(state="ready")
    log("ready; waiting for jobs")

    while True:
        job_id, job = next_queued_job()
        if job_id is None:
            time.sleep(0.5)
            continue

        set_worker_status(state="busy", job_id=job_id)
        try:
            process_job(job_id, job)
        except Exception as e:
            log(f"{job_id}: FAILED {e}")
            traceback.print_exc()
            code, msg = classify_error(e)
            write_state(job_id, status="failed", error_code=code, error_message=msg,
                        finished_at=time.time())
            _empty_cache()
        set_worker_status(state="ready")


def classify_error(e: Exception):
    text = str(e).lower()
    if _is_oom(e):
        return "ERR_OOM", "Video is size pe process nahi ho payi."
    if "cuda" in text or "driver" in text:
        return "ERR_GPU", "Server ka GPU available nahi hai."
    if "decord" in text or "cannot open" in text or "codec" in text:
        return "ERR_DECODE", "Video format support nahi hai."
    if "validation failed" in text:
        return "ERR_QUALITY", "Output quality check fail ho gaya. Dobara try karo."
    if "no space" in text:
        return "ERR_DISK", "Server par jagah khatam ho gayi."
    return "ERR_SERVER", "Server par process fail ho gaya."


def next_queued_job():
    """Oldest queued job wins. One at a time — two jobs on one GPU is an OOM."""
    candidates = []
    for job_id, job, state in list_jobs():
        if state.get("status") == "queued":
            candidates.append((job.get("created_at", 0), job_id, job))
    if not candidates:
        return None, None
    candidates.sort()
    _, job_id, job = candidates[0]
    return job_id, job


if __name__ == "__main__":
    sys.exit(main())

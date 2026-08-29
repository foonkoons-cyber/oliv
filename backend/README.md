# DepthMaker backend

FastAPI HTTP layer + a **separate** GPU worker process running
[Video Depth Anything](https://github.com/DepthAnything/Video-Depth-Anything).

```
android app ──HTTPS──▶ server.py (no GPU, owns the queue)
                          │ spawn / SIGKILL / respawn
                          ▼
                       worker.py (owns the GPU, one job at a time)
```

The split is not decoration. A CUDA call cannot be interrupted from inside the
process that issued it, so "cancel frees the GPU within 5 seconds" is only true
if cancel is `SIGKILL` of a process that is not the API.

## Requirements

```
Ubuntu 22.04 · NVIDIA GPU (>= 8 GB for vits, >= 24 GB for vitl)
CUDA 12.1+  · Python 3.10 or 3.11   (NOT 3.12)
```

## No GPU? Test it free on Colab

Open [`colab/DepthMaker_Colab.ipynb`](colab/DepthMaker_Colab.ipynb) in Google Colab:

**https://colab.research.google.com/github/foonkoons-cyber/oliv/blob/claude/build-app-apk-cr4a7j/backend/colab/DepthMaker_Colab.ipynb**

Set Runtime → Change runtime type → **T4 GPU**, then run the cells. It installs the
backend, downloads the Small (`vits`) checkpoint, starts the server and opens a
Cloudflare quick tunnel, then prints a `https://….trycloudflare.com` URL and a
freshly generated token to paste into the app's Settings.

This is for testing. The session drops after roughly 90 minutes idle (12 hours max),
the tunnel URL changes every run, and a T4 is much slower than the benchmark A100 —
a 10-second clip takes a few minutes. For client work, run it on a rented GPU box
with a stable domain as below.

## Setup

```bash
export VDA_ROOT=$HOME/Video-Depth-Anything
./setup.sh
```

`setup.sh` clones the model repo, fetches weights, installs dependencies and
applies the progress patch. It deliberately does **not** run upstream's
`pip install -r requirements.txt`: that pins `numpy==1.24.0`, which fails to
build on Python 3.12 and on newer pip/setuptools. `einops` and `tqdm` are hard
imports of the model code and are installed explicitly.

## Run

```bash
export VDA_ROOT=$HOME/Video-Depth-Anything
export DEPTHMAKER_TOKEN=$(openssl rand -hex 32)
uvicorn server:app --host 0.0.0.0 --port 8000 --workers 1
```

`--workers 1` is mandatory — additional uvicorn workers would each spawn their
own GPU worker and queue.

| Env var | Default | Meaning |
|---|---|---|
| `DEPTHMAKER_TOKEN` | — | **Required.** Shared bearer token. |
| `VDA_ROOT` | `~/Video-Depth-Anything` | Model checkout. |
| `PRELOAD_MODELS` | `vits` | Comma list kept resident in VRAM. |
| `MAX_RES` | `1280` | Inference-time long-edge cap; output is resized back. |
| `RETENTION_SECONDS` | `3600` | Input + output deleted this long after finishing. |
| `IDLE_SHUTDOWN_MINUTES` | `0` (off) | Set to `15` on rented GPUs to stop the bill. |
| `MIN_FREE_BYTES` | 5 GB | Health check fails below this; no new jobs accepted. |
| `SKIP_SELF_TEST` | `0` | Only for debugging. Leave the self-test on. |

Startup order: verify checkpoints (fail here, not on the first request) → load
the model → run the bundled self-test → accept jobs. `/health` reports
`503` until the worker is ready, and `accepting_jobs: false` when disk is low.

## What the pipeline guarantees

* **Source resolution out.** `read_video_frames` downscales anything above
  `max_res`; `resize_depths_to_source` undoes it so depth aligns pixel-for-pixel
  with the source for ControlNet/parallax. 1080p in → 1080p out.
* **One pass, one normalization.** Never chunked; whole-sequence min/max only.
  Per-chunk normalization is what puts a brightness jump mid-video.
* **Pure grayscale, near = white.** Frames are built as `R=G=B`; the gate
  re-checks the encoded file with a tolerance of 2 for the yuv420p round-trip.
* **No banding.** `-crf 14 -preset slow -pix_fmt yuv420p -an`, not upstream
  `save_video`'s hardcoded CRF 18.
* **Real progress.** `patch_progress.py` shims `tqdm` inside `video_depth.py`
  so each completed window reports through to the client. ETA comes from the
  live measured rate of the running job, never a benchmark table.

Validation before a job reports `done` — hard failures (frame count, fps,
resolution, grayscale) fail the job; luminance and std-dev are warnings only,
because a dark scene or a flat wall is legitimate footage.

## API

All endpoints require `Authorization: Bearer <token>`; a bad token is `401`.

```
POST   /uploads              {filename,size_bytes,sha256} → {upload_id,chunk_size,received_bytes}
PUT    /uploads/{id}         Content-Range: bytes s-e/total, raw body → {received_bytes}
GET    /uploads/{id}         → {received_bytes}          # resume offset after a drop
POST   /jobs                 {upload_id,model,format}     → {job_id,status,queue_position}
GET    /jobs/{id}            → status/progress/stage_text/eta_seconds/result_url
GET    /jobs/{id}/result     → video/mp4 | application/zip | application/octet-stream
DELETE /jobs/{id}            → 204                        # kills the worker, frees VRAM
GET    /health               → 200/503
```

Chunk PUTs are idempotent and a gap returns `416` with the expected offset, so a
dropped upload resumes instead of restarting.

## Tests

```bash
pip install -r requirements-test.txt
pytest -q          # 20 tests, no GPU needed
```

They cover the resize-back step, CRF-14 encoding, grayscale and polarity,
global-vs-per-frame normalization, a static-scene flicker check, PNG-16 and NPZ
output, the validation gate's failure modes, and the resumable upload contract.
GPU-dependent items in spec §8 (real flicker on camera footage, the 30-second
continuity plot, side-by-side against the reference clip) must still be run on
the deployment box with real footage.

## Deployment notes

* HTTPS only — the app rejects `http://`, and Android blocks cleartext anyway.
* Put a long random token in `DEPTHMAKER_TOKEN`. An open upload endpoint on a
  GPU box gets found.
* Set `IDLE_SHUTDOWN_MINUTES=15` on RunPod/Vast. GPU hosting is a monthly cost,
  not a one-time one.
* Client footage is deleted an hour after the job finishes; don't raise
  `RETENTION_SECONDS` on a rented machine.

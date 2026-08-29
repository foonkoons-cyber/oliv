"""
DepthMaker API (spec 3.7 / 3.8).

The FastAPI process handles HTTP only: uploads, the job queue, job state, and
supervision of the GPU worker. It never touches the GPU itself, which is what
makes cancel (SIGKILL + respawn) actually work.

Run with exactly one uvicorn worker:
    uvicorn server:app --host 0.0.0.0 --port 8000 --workers 1
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

import jobstate as js

CHUNK_SIZE = 5 * 1024 * 1024
RETENTION_SECONDS = int(os.environ.get("RETENTION_SECONDS", str(60 * 60)))   # 1 hour
MAX_UPLOAD_BYTES = int(os.environ.get("MAX_UPLOAD_BYTES", str(200 * 1024 * 1024)))
MIN_FREE_BYTES = int(os.environ.get("MIN_FREE_BYTES", str(5 * 1024 * 1024 * 1024)))
AUTH_TOKEN = os.environ.get("DEPTHMAKER_TOKEN", "")
RATE_LIMIT_PER_MINUTE = int(os.environ.get("RATE_LIMIT_PER_MINUTE", "120"))
IDLE_SHUTDOWN_MINUTES = int(os.environ.get("IDLE_SHUTDOWN_MINUTES", "0"))   # 0 = off

@asynccontextmanager
async def lifespan(_app: "FastAPI"):
    js.ensure_dirs()
    if not AUTH_TOKEN:
        print("[api] FATAL: DEPTHMAKER_TOKEN is not set", flush=True)
        raise RuntimeError("DEPTHMAKER_TOKEN is required")
    spawn_worker()
    if os.environ.get("DEPTHMAKER_NO_WORKER") != "1":
        threading.Thread(target=_supervisor_loop, daemon=True).start()
    yield


app = FastAPI(title="DepthMaker", version="1.1.0", lifespan=lifespan)

_worker_proc: subprocess.Popen | None = None
_worker_lock = threading.Lock()
_rate_state: dict[str, list] = {}
_last_activity = time.time()


# --------------------------------------------------------------------------
# auth + rate limiting
# --------------------------------------------------------------------------

def require_auth(authorization: str = Header(default="")) -> str:
    """A GPU box with an open upload endpoint gets found and abused."""
    if not AUTH_TOKEN:
        raise HTTPException(500, "server misconfigured: DEPTHMAKER_TOKEN is not set")
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing bearer token")
    token = authorization[len("Bearer "):].strip()
    if not _consteq(token, AUTH_TOKEN):
        raise HTTPException(401, "invalid token")
    _rate_limit(token)
    global _last_activity
    _last_activity = time.time()
    return token


def _consteq(a: str, b: str) -> bool:
    import hmac
    return hmac.compare_digest(a.encode(), b.encode())


def _rate_limit(token: str) -> None:
    now = time.time()
    hits = _rate_state.setdefault(token, [])
    hits[:] = [t for t in hits if now - t < 60]
    if len(hits) >= RATE_LIMIT_PER_MINUTE:
        raise HTTPException(429, "rate limit exceeded")
    hits.append(now)


# --------------------------------------------------------------------------
# worker supervision (spec 3.8)
# --------------------------------------------------------------------------

def spawn_worker() -> None:
    """No-op when DEPTHMAKER_NO_WORKER=1 (API tests on a GPU-less box)."""
    global _worker_proc
    if os.environ.get("DEPTHMAKER_NO_WORKER") == "1":
        return
    with _worker_lock:
        if _worker_proc is not None and _worker_proc.poll() is None:
            return
        env = dict(os.environ)
        _worker_proc = subprocess.Popen(
            [sys.executable, os.path.join(os.path.dirname(__file__), "worker.py")],
            env=env,
        )
        print(f"[api] worker spawned pid={_worker_proc.pid}", flush=True)


def kill_worker() -> None:
    """Cancel means SIGKILL: an in-flight CUDA call cannot be asked politely."""
    global _worker_proc
    with _worker_lock:
        if _worker_proc is None:
            return
        if _worker_proc.poll() is None:
            try:
                os.kill(_worker_proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            _worker_proc.wait(timeout=10)
        _worker_proc = None


def worker_alive() -> bool:
    return _worker_proc is not None and _worker_proc.poll() is None


def _supervisor_loop() -> None:
    """Detect a dead worker and never leave its job stuck in 'processing'."""
    while True:
        time.sleep(2)
        try:
            if not worker_alive():
                for job_id, job, state in js.list_jobs():
                    if state.get("status") in ("processing", "encoding"):
                        js.write_state(
                            job_id,
                            status="failed",
                            error_code="ERR_GPU",
                            error_message="Server ka GPU available nahi hai.",
                            finished_at=time.time(),
                        )
                spawn_worker()
            _reap_expired()
            _maybe_idle_shutdown()
        except Exception as e:   # never let the supervisor die
            print(f"[api] supervisor error: {e}", flush=True)


def _reap_expired() -> None:
    """Client footage must not linger on a rented GPU box: delete input and
    output one hour after the job finished."""
    now = time.time()
    for job_id, job, state in js.list_jobs():
        finished = state.get("finished_at")
        if finished and now - finished > RETENTION_SECONDS:
            shutil.rmtree(js.job_dir(job_id), ignore_errors=True)
    if os.path.isdir(js.UPLOADS_DIR):
        for name in os.listdir(js.UPLOADS_DIR):
            path = os.path.join(js.UPLOADS_DIR, name)
            try:
                if now - os.path.getmtime(path) > RETENTION_SECONDS:
                    os.remove(path)
            except OSError:
                pass


def _maybe_idle_shutdown() -> None:
    if IDLE_SHUTDOWN_MINUTES <= 0:
        return
    if time.time() - _last_activity < IDLE_SHUTDOWN_MINUTES * 60:
        return
    if any(s.get("status") in ("queued", "processing", "encoding") for _, _, s in js.list_jobs()):
        return
    print("[api] idle timeout reached — shutting down to stop the GPU bill", flush=True)
    os.kill(os.getpid(), signal.SIGTERM)


# --------------------------------------------------------------------------
# health
# --------------------------------------------------------------------------

@app.get("/health")
def health():
    ws = js.read_worker_status()
    free = shutil.disk_usage(js.DATA_DIR).free
    ok = ws.get("state") in ("ready", "busy") and free > MIN_FREE_BYTES
    return JSONResponse(
        status_code=200 if ok else 503,
        content={
            "ok": ok,
            "worker": ws.get("state", "unknown"),
            "worker_error": ws.get("error"),
            "free_bytes": free,
            "accepting_jobs": ok,
        },
    )


def _assert_accepting() -> None:
    free = shutil.disk_usage(js.DATA_DIR).free
    if free < MIN_FREE_BYTES:
        raise HTTPException(503, "server out of disk space")
    ws = js.read_worker_status()
    if ws.get("state") == "failed":
        raise HTTPException(503, f"worker failed to start: {ws.get('error')}")


# --------------------------------------------------------------------------
# chunked resumable upload (spec 3.7)
# --------------------------------------------------------------------------

class CreateUpload(BaseModel):
    filename: str
    size_bytes: int = Field(ge=1)
    sha256: str = ""


def _upload_paths(upload_id: str):
    return (
        os.path.join(js.UPLOADS_DIR, f"{upload_id}.bin"),
        os.path.join(js.UPLOADS_DIR, f"{upload_id}.json"),
    )


def _safe_id(value: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", value or ""):
        raise HTTPException(404, "not found")
    return value


@app.post("/uploads", status_code=201)
def create_upload(body: CreateUpload, _: str = Depends(require_auth)):
    _assert_accepting()
    if body.size_bytes > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "file too large")
    upload_id = str(uuid.uuid4())   # never sequential: no enumerating other users' videos
    blob, meta = _upload_paths(upload_id)
    open(blob, "wb").close()
    js._atomic_write(meta, {
        "upload_id": upload_id,
        "filename": os.path.basename(body.filename)[:128],
        "size_bytes": body.size_bytes,
        "sha256": body.sha256,
        "created_at": time.time(),
    })
    return {"upload_id": upload_id, "chunk_size": CHUNK_SIZE, "received_bytes": 0}


@app.get("/uploads/{upload_id}")
def upload_status(upload_id: str, _: str = Depends(require_auth)):
    blob, meta = _upload_paths(_safe_id(upload_id))
    if not os.path.exists(meta):
        raise HTTPException(404, "unknown upload")
    return {"received_bytes": os.path.getsize(blob) if os.path.exists(blob) else 0}


@app.put("/uploads/{upload_id}")
async def upload_chunk(
    upload_id: str,
    request: Request,
    content_range: str = Header(default=""),
    _: str = Depends(require_auth),
):
    blob, meta = _upload_paths(_safe_id(upload_id))
    if not os.path.exists(meta):
        raise HTTPException(404, "unknown upload")

    m = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", content_range.strip())
    if not m:
        raise HTTPException(400, "Content-Range required as 'bytes start-end/total'")
    start, end, total = int(m.group(1)), int(m.group(2)), int(m.group(3))
    if total > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "file too large")

    data = await request.body()
    if len(data) != end - start + 1:
        raise HTTPException(400, "chunk length does not match Content-Range")

    have = os.path.getsize(blob)
    if start > have:
        # A gap would silently corrupt the file; tell the client where to resume.
        raise HTTPException(416, f"expected offset {have}")

    # Idempotent: re-sending an already-stored chunk is a no-op.
    if start + len(data) > have:
        with open(blob, "r+b") as f:
            f.seek(start)
            f.write(data)

    return {"received_bytes": os.path.getsize(blob)}


# --------------------------------------------------------------------------
# jobs
# --------------------------------------------------------------------------

class CreateJob(BaseModel):
    upload_id: str
    model: str = "vits"
    format: str = "mp4"


@app.post("/jobs", status_code=202)
def create_job(body: CreateJob, _: str = Depends(require_auth)):
    _assert_accepting()
    if body.model not in ("vits", "vitb", "vitl"):
        raise HTTPException(400, "unknown model")
    if body.format not in ("mp4", "png16", "npz"):
        raise HTTPException(400, "unknown format")

    blob, meta = _upload_paths(_safe_id(body.upload_id))
    info = js._read_json(meta)
    if not info:
        raise HTTPException(404, "unknown upload")
    if not os.path.exists(blob) or os.path.getsize(blob) != info["size_bytes"]:
        raise HTTPException(400, "upload incomplete")
    if info.get("sha256"):
        digest = _sha256(blob)
        if digest != info["sha256"]:
            raise HTTPException(400, "upload checksum mismatch")

    job_id = str(uuid.uuid4())
    d = js.job_dir(job_id)
    os.makedirs(d, exist_ok=True)
    shutil.move(blob, os.path.join(d, "input.mp4"))
    try:
        os.remove(meta)
    except OSError:
        pass

    js.write_job(job_id, {
        "job_id": job_id,
        "model": body.model,
        "format": body.format,
        "filename": info.get("filename", "video.mp4"),
        "created_at": time.time(),
    })
    js.write_state(job_id, status="queued", progress=0, stage_text="Server par queue me hai")
    return {"job_id": job_id, "status": "queued", "queue_position": _queue_position(job_id)}


def _queue_position(job_id: str) -> int:
    created = js.read_job(job_id).get("created_at", 0)
    ahead = 0
    for other_id, job, state in js.list_jobs():
        if other_id == job_id:
            continue
        if state.get("status") in ("queued", "processing", "encoding") and \
                job.get("created_at", 0) < created:
            ahead += 1
    return ahead


@app.get("/jobs/{job_id}")
def job_status(job_id: str, _: str = Depends(require_auth)):
    job = js.read_job(_safe_id(job_id))
    if not job:
        raise HTTPException(404, "unknown job")
    state = js.read_state(job_id)
    status = state.get("status", "queued")

    payload = {
        "job_id": job_id,
        "status": status,
        "progress": int(state.get("progress", 0)),
        "stage_text": state.get("stage_text"),
        "error_code": state.get("error_code"),
        "error_message": state.get("error_message"),
        "warnings": state.get("warnings", []),
    }
    if status == "queued":
        pos = _queue_position(job_id)
        payload["queue_position"] = pos
        payload["stage_text"] = (
            f"{pos} video aapse aage hain" if pos > 0 else "Server par shuru hone wali hai"
        )
    if state.get("eta_seconds"):
        payload["eta_seconds"] = int(state["eta_seconds"])
    if status == "done":
        payload["result_url"] = f"/jobs/{job_id}/result"
    return payload


@app.get("/jobs/{job_id}/result")
def job_result(job_id: str, _: str = Depends(require_auth)):
    job = js.read_job(_safe_id(job_id))
    state = js.read_state(job_id)
    if not job or state.get("status") != "done":
        raise HTTPException(404, "result not ready")
    name = state.get("result_file")
    path = os.path.join(js.job_dir(job_id), name or "")
    if not name or not os.path.exists(path):
        raise HTTPException(404, "result missing")
    media = {
        ".mp4": "video/mp4",
        ".zip": "application/zip",
        ".npz": "application/octet-stream",
    }[os.path.splitext(name)[1]]
    return FileResponse(path, media_type=media, filename=f"depth_{job.get('filename', 'out')}")


@app.delete("/jobs/{job_id}", status_code=204)
def cancel_job(job_id: str, _: str = Depends(require_auth)):
    job = js.read_job(_safe_id(job_id))
    if not job:
        raise HTTPException(404, "unknown job")
    state = js.read_state(job_id)
    ws = js.read_worker_status()

    js.write_state(job_id, status="failed", error_code="ERR_CANCELLED",
                   error_message="Cancel kar diya.", finished_at=time.time())

    if state.get("status") in ("processing", "encoding") or ws.get("job_id") == job_id:
        kill_worker()          # frees VRAM immediately
        shutil.rmtree(js.job_dir(job_id), ignore_errors=True)
        spawn_worker()         # 10-30 s model reload; cancels are rare
    else:
        shutil.rmtree(js.job_dir(job_id), ignore_errors=True)
    return JSONResponse(status_code=204, content=None)


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

"""Filesystem-backed shared state between the API process and the GPU worker.

Deliberately boring: one directory per job, atomic replace on every write.
No Redis to run, no shared memory to corrupt, and `ls` tells you what is going on.
"""
from __future__ import annotations

import json
import os
import time

DATA_DIR = os.environ.get("DEPTHMAKER_DATA", os.path.join(os.path.dirname(__file__), "data"))
UPLOADS_DIR = os.path.join(DATA_DIR, "uploads")
JOBS_DIR = os.path.join(DATA_DIR, "jobs")
WORKER_STATUS_FILE = os.path.join(DATA_DIR, "worker_status.json")


def ensure_dirs() -> None:
    for d in (DATA_DIR, UPLOADS_DIR, JOBS_DIR):
        os.makedirs(d, exist_ok=True)


def _atomic_write(path: str, payload: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.tmp"
    with open(tmp, "w") as f:
        json.dump(payload, f)
    os.replace(tmp, path)


def _read_json(path: str) -> dict:
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def job_dir(job_id: str) -> str:
    return os.path.join(JOBS_DIR, job_id)


def write_job(job_id: str, job: dict) -> None:
    _atomic_write(os.path.join(job_dir(job_id), "job.json"), job)


def read_job(job_id: str) -> dict:
    return _read_json(os.path.join(job_dir(job_id), "job.json"))


def write_state(job_id: str, **fields) -> None:
    path = os.path.join(job_dir(job_id), "state.json")
    state = _read_json(path)
    state.update(fields)
    state["updated_at"] = time.time()
    _atomic_write(path, state)


def read_state(job_id: str) -> dict:
    return _read_json(os.path.join(job_dir(job_id), "state.json"))


def read_worker_status() -> dict:
    return _read_json(WORKER_STATUS_FILE)


def list_jobs():
    if not os.path.isdir(JOBS_DIR):
        return []
    out = []
    for job_id in os.listdir(JOBS_DIR):
        d = os.path.join(JOBS_DIR, job_id)
        if not os.path.isdir(d):
            continue
        job = _read_json(os.path.join(d, "job.json"))
        state = _read_json(os.path.join(d, "state.json"))
        if job:
            out.append((job_id, job, state))
    return out

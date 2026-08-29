"""API tests: auth, the resumable upload contract, and job lifecycle.
Runs without a GPU — the worker is stubbed out with DEPTHMAKER_NO_WORKER=1."""
from __future__ import annotations

import importlib
import os
import tempfile

import pytest
from fastapi.testclient import TestClient

TOKEN = "test-token-123"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("DEPTHMAKER_DATA", str(tmp_path / "data"))
    monkeypatch.setenv("DEPTHMAKER_TOKEN", TOKEN)
    monkeypatch.setenv("DEPTHMAKER_NO_WORKER", "1")
    import jobstate
    importlib.reload(jobstate)
    import server
    importlib.reload(server)
    with TestClient(server.app) as c:
        yield c


def auth(token=TOKEN):
    return {"Authorization": f"Bearer {token}"}


def test_every_endpoint_requires_a_bearer_token(client):
    assert client.post("/uploads", json={"filename": "a.mp4", "size_bytes": 10}).status_code == 401
    assert client.get("/jobs/" + "0" * 36).status_code == 401


def test_wrong_token_is_401_not_500(client):
    r = client.post("/uploads", json={"filename": "a.mp4", "size_bytes": 10},
                    headers=auth("nope"))
    assert r.status_code == 401


def test_chunked_upload_resumes_from_the_received_offset(client):
    payload = os.urandom(300_000)
    r = client.post("/uploads", json={"filename": "clip.mp4", "size_bytes": len(payload),
                                      "sha256": ""}, headers=auth())
    assert r.status_code == 201
    upload_id = r.json()["upload_id"]
    assert r.json()["received_bytes"] == 0

    half = len(payload) // 2
    r = client.put(f"/uploads/{upload_id}", content=payload[:half],
                   headers={**auth(), "Content-Range": f"bytes 0-{half - 1}/{len(payload)}"})
    assert r.status_code == 200 and r.json()["received_bytes"] == half

    # simulate a dropped connection: the client asks where to resume
    assert client.get(f"/uploads/{upload_id}", headers=auth()).json()["received_bytes"] == half

    # re-sending the same chunk is idempotent, not a corruption
    r = client.put(f"/uploads/{upload_id}", content=payload[:half],
                   headers={**auth(), "Content-Range": f"bytes 0-{half - 1}/{len(payload)}"})
    assert r.json()["received_bytes"] == half

    rest = payload[half:]
    r = client.put(f"/uploads/{upload_id}", content=rest,
                   headers={**auth(),
                            "Content-Range": f"bytes {half}-{len(payload) - 1}/{len(payload)}"})
    assert r.json()["received_bytes"] == len(payload)


def test_a_gap_in_the_upload_is_refused(client):
    r = client.post("/uploads", json={"filename": "clip.mp4", "size_bytes": 1000, "sha256": ""},
                    headers=auth())
    upload_id = r.json()["upload_id"]
    r = client.put(f"/uploads/{upload_id}", content=b"x" * 100,
                   headers={**auth(), "Content-Range": "bytes 500-599/1000"})
    assert r.status_code == 416


def test_job_lifecycle_and_ids_are_uuids(client):
    import hashlib
    payload = b"not-really-a-video" * 100
    digest = hashlib.sha256(payload).hexdigest()
    up = client.post("/uploads", json={"filename": "clip.mp4", "size_bytes": len(payload),
                                       "sha256": digest}, headers=auth()).json()
    client.put(f"/uploads/{up['upload_id']}", content=payload,
               headers={**auth(), "Content-Range": f"bytes 0-{len(payload) - 1}/{len(payload)}"})

    r = client.post("/jobs", json={"upload_id": up["upload_id"], "model": "vits",
                                   "format": "mp4"}, headers=auth())
    assert r.status_code == 202
    job = r.json()
    assert len(job["job_id"]) == 36 and job["status"] == "queued"

    st = client.get(f"/jobs/{job['job_id']}", headers=auth()).json()
    assert st["status"] == "queued"
    assert "queue_position" in st
    assert st["stage_text"]                    # the screen must never look frozen

    assert client.get(f"/jobs/{job['job_id']}/result", headers=auth()).status_code == 404
    assert client.delete(f"/jobs/{job['job_id']}", headers=auth()).status_code == 204
    assert client.get(f"/jobs/{job['job_id']}", headers=auth()).status_code == 404


def test_checksum_mismatch_is_rejected(client):
    payload = b"abcdef" * 100
    up = client.post("/uploads", json={"filename": "clip.mp4", "size_bytes": len(payload),
                                       "sha256": "0" * 64}, headers=auth()).json()
    client.put(f"/uploads/{up['upload_id']}", content=payload,
               headers={**auth(), "Content-Range": f"bytes 0-{len(payload) - 1}/{len(payload)}"})
    r = client.post("/jobs", json={"upload_id": up["upload_id"]}, headers=auth())
    assert r.status_code == 400


def test_unknown_model_or_format_is_rejected(client):
    payload = b"x" * 10
    up = client.post("/uploads", json={"filename": "c.mp4", "size_bytes": 10, "sha256": ""},
                     headers=auth()).json()
    client.put(f"/uploads/{up['upload_id']}", content=payload,
               headers={**auth(), "Content-Range": "bytes 0-9/10"})
    assert client.post("/jobs", json={"upload_id": up["upload_id"], "model": "gpt"},
                       headers=auth()).status_code == 400
    assert client.post("/jobs", json={"upload_id": up["upload_id"], "format": "gif"},
                       headers=auth()).status_code == 400


def test_ids_are_not_enumerable(client):
    r = client.get("/jobs/1", headers=auth())
    assert r.status_code == 404

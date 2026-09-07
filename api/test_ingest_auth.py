import importlib

import pytest
from fastapi.testclient import TestClient


def _reload_app(monkeypatch, api_key):
    if api_key is None:
        monkeypatch.delenv("INGEST_API_KEY", raising=False)
    else:
        monkeypatch.setenv("INGEST_API_KEY", api_key)
    import main
    importlib.reload(main)
    return main


def test_ingest_open_when_no_api_key_configured(monkeypatch):
    main = _reload_app(monkeypatch, None)
    client = TestClient(main.app)
    resp = client.post("/ingest", json={})
    assert resp.status_code == 200


def test_ingest_rejects_missing_header_when_key_configured(monkeypatch):
    main = _reload_app(monkeypatch, "secret123")
    client = TestClient(main.app)
    resp = client.post("/ingest", json={})
    assert resp.status_code == 401


def test_ingest_rejects_wrong_key(monkeypatch):
    main = _reload_app(monkeypatch, "secret123")
    client = TestClient(main.app)
    resp = client.post("/ingest", json={}, headers={"X-API-Key": "wrong"})
    assert resp.status_code == 401


def test_ingest_accepts_correct_key(monkeypatch):
    main = _reload_app(monkeypatch, "secret123")
    client = TestClient(main.app)
    resp = client.post("/ingest", json={}, headers={"X-API-Key": "secret123"})
    assert resp.status_code == 200

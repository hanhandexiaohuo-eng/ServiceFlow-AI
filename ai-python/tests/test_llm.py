"""模型客户端测试：用假 HTTP 客户端验证重试与错误码，不产生真实费用。"""
import asyncio
import json

import httpx
import pytest

import app.llm as llm_module
from app.config import Settings
from app.errors import AiError
from app.llm import DeepSeekClient


class FakeResponse:
    def __init__(self, status_code: int, payload: dict | None = None) -> None:
        self.status_code = status_code
        self._payload = payload

    def json(self):
        if self._payload is None:
            raise ValueError("not json")
        return self._payload


class FakeAsyncClient:
    def __init__(self, responses: list, recorder: list) -> None:
        self.responses = responses
        self.recorder = recorder

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def post(self, url, json=None, headers=None):
        self.recorder.append({"url": url, "json": json, "headers": headers})
        item = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(item, Exception):
            raise item
        return item


def make_client(monkeypatch, responses: list) -> tuple[DeepSeekClient, list]:
    recorder: list = []
    monkeypatch.setattr(
        llm_module.httpx,
        "AsyncClient",
        lambda **kwargs: FakeAsyncClient(responses, recorder),
    )
    settings = Settings(
        api_key="unit-test-key",
        base_url="https://api.deepseek.com",
        model="deepseek-chat",
        timeout_seconds=5,
        rate_limit_per_minute=10,
        retry_delay_seconds=0,
    )
    return DeepSeekClient(settings), recorder


def test_success_returns_content(monkeypatch):
    client, recorder = make_client(
        monkeypatch,
        [FakeResponse(200, {"choices": [{"message": {"content": '{"ok": true}'}}]})],
    )
    content, model = asyncio.run(client.chat_json("sys", "user", "req"))
    assert content == '{"ok": true}'
    assert model == "deepseek-chat"
    assert recorder[0]["url"].endswith("/chat/completions")
    assert recorder[0]["headers"]["Authorization"] == "Bearer unit-test-key"


def test_429_retries_then_rate_limited(monkeypatch):
    client, recorder = make_client(monkeypatch, [FakeResponse(429), FakeResponse(429), FakeResponse(429)])
    with pytest.raises(AiError) as exc:
        asyncio.run(client.chat_json("sys", "user", "req"))
    assert exc.value.code == "AI_RATE_LIMITED"
    assert len(recorder) == 3


def test_500_then_success(monkeypatch):
    client, recorder = make_client(
        monkeypatch,
        [
            FakeResponse(500),
            FakeResponse(200, {"choices": [{"message": {"content": json.dumps({"ok": 1})}}]}),
        ],
    )
    content, _ = asyncio.run(client.chat_json("sys", "user", "req"))
    assert content == '{"ok": 1}'
    assert len(recorder) == 2


def test_timeout(monkeypatch):
    client, recorder = make_client(monkeypatch, [httpx.TimeoutException("timeout")])
    with pytest.raises(AiError) as exc:
        asyncio.run(client.chat_json("sys", "user", "req"))
    assert exc.value.code == "AI_TIMEOUT"
    assert len(recorder) == 3


def test_connection_error(monkeypatch):
    client, _ = make_client(monkeypatch, [httpx.ConnectError("refused")])
    with pytest.raises(AiError) as exc:
        asyncio.run(client.chat_json("sys", "user", "req"))
    assert exc.value.code == "AI_UNAVAILABLE"


def test_empty_content(monkeypatch):
    client, _ = make_client(monkeypatch, [FakeResponse(200, {"choices": [{"message": {"content": "   "}}]})])
    with pytest.raises(AiError) as exc:
        asyncio.run(client.chat_json("sys", "user", "req"))
    assert exc.value.code == "AI_EMPTY_RESPONSE"


def test_unauthorized(monkeypatch):
    client, recorder = make_client(monkeypatch, [FakeResponse(401)])
    with pytest.raises(AiError) as exc:
        asyncio.run(client.chat_json("sys", "user", "req"))
    assert exc.value.code == "AI_CONFIG_INVALID"
    assert len(recorder) == 1


def test_invalid_upstream_json(monkeypatch):
    client, _ = make_client(monkeypatch, [FakeResponse(200, None)])
    with pytest.raises(AiError) as exc:
        asyncio.run(client.chat_json("sys", "user", "req"))
    assert exc.value.code == "AI_INVALID_OUTPUT"

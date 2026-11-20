"""API 层测试：路由、错误映射、就绪检查。"""
import json

import pytest
from fastapi.testclient import TestClient

from app.errors import AiError
from app.main import app
from app.service import AnalysisService, RateLimiter, get_analysis_service

TICKET = {
    "ticketNo": "SF20261116-YWEF",
    "title": "终端疑似勒索病毒",
    "description": "安全软件提示发现可疑加密行为，终端已断网，需要隔离取证并评估扩散范围。",
    "type": "INCIDENT",
    "priority": "URGENT",
    "status": "ASSIGNED",
}

RESULT = {
    "recommendedType": "INCIDENT",
    "typeConfidence": 0.9,
    "problemCategory": "终端安全事件",
    "summary": "终端出现疑似勒索加密行为，需要隔离取证并评估扩散范围。",
    "keyFacts": ["安全软件提示可疑加密行为"],
    "technicalDomains": ["终端安全"],
    "requiredSkills": ["系统运维"],
    "impactScope": "单台终端及同网段共享目录",
    "recommendedPriority": "URGENT",
    "priorityConfidence": 0.9,
    "keywords": ["勒索病毒"],
    "reasons": ["描述包含可疑加密行为"],
    "confidence": 0.85,
}


class FakeClient:
    def __init__(self, content: str = "", error: Exception | None = None) -> None:
        self.content = content
        self.error = error

    async def chat_json(self, system: str, user: str, request_id: str):
        if self.error:
            raise self.error
        return self.content, "deepseek-chat"


def override(client) -> None:
    service = AnalysisService(client=client, limiter=RateLimiter(100))  # type: ignore[arg-type]
    app.dependency_overrides[get_analysis_service] = lambda: service


@pytest.fixture(autouse=True)
def clear_overrides():
    yield
    app.dependency_overrides.clear()


def test_health():
    with TestClient(app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_readiness_reports_missing_key(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    with TestClient(app) as client:
        response = client.get("/health/ready")
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "DOWN"
    assert any("DEEPSEEK_API_KEY" in item for item in body["issues"])


def test_readiness_up_with_key(monkeypatch):
    monkeypatch.setenv("DEEPSEEK_API_KEY", "unit-test-key")
    with TestClient(app) as client:
        response = client.get("/health/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert "ticket_analysis@v1" in body["prompts"]


def test_ticket_analysis_endpoint():
    override(FakeClient(json.dumps(RESULT)))
    with TestClient(app) as client:
        response = client.post(
            "/ai/ticket-analysis",
            json={"requestId": "req-1", "payload": {"ticket": TICKET}},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == "req-1"
    assert body["promptVersion"] == "ticket_analysis@v1"
    assert body["result"]["recommendedPriority"] == "URGENT"


def test_error_maps_to_status_code():
    override(FakeClient(error=AiError("AI_CONFIG_MISSING", "AI 服务未配置 DEEPSEEK_API_KEY", 503)))
    with TestClient(app) as client:
        response = client.post(
            "/ai/ticket-analysis",
            json={"requestId": "req-2", "payload": {"ticket": TICKET}},
        )
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "AI_CONFIG_MISSING"
    assert response.json()["error"]["requestId"] == "req-2"


def test_invalid_output_maps_to_422():
    override(FakeClient("not-json"))
    with TestClient(app) as client:
        response = client.post(
            "/ai/ticket-analysis",
            json={"payload": {"ticket": TICKET}},
        )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "AI_INVALID_OUTPUT"


def test_missing_ticket_context_is_400():
    override(FakeClient(json.dumps(RESULT)))
    with TestClient(app) as client:
        response = client.post("/ai/ticket-analysis", json={"payload": {}})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "AI_BAD_REQUEST"

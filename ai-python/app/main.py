"""ServiceFlow AI - Python AI 能力服务（第一阶段：仅健康检查骨架）。"""
import os
from datetime import datetime, timezone

from fastapi import FastAPI
from pydantic import BaseModel

SERVICE_NAME = "serviceflow-ai-python"
VERSION = "0.1.0"

app = FastAPI(title=SERVICE_NAME, version=VERSION)


class HealthResponse(BaseModel):
    service: str
    status: str
    version: str
    timestamp: datetime


class ReadinessResponse(BaseModel):
    service: str
    status: str
    version: str
    timestamp: datetime
    checks: list[dict]


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        service=SERVICE_NAME,
        status="UP",
        version=VERSION,
        timestamp=datetime.now(timezone.utc),
    )


@app.get("/health/ready", response_model=ReadinessResponse)
def ready() -> ReadinessResponse:
    # 本阶段无外部依赖，直接报告 UP；接入 DeepSeek 后在此增加真实检查
    return ReadinessResponse(
        service=SERVICE_NAME,
        status="UP",
        version=VERSION,
        timestamp=datetime.now(timezone.utc),
        checks=[],
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=os.environ.get("AI_HOST", "127.0.0.1"),
        port=int(os.environ.get("AI_PORT", "8000")),
    )

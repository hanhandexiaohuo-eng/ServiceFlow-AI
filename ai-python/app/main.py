"""ServiceFlow AI 服务入口：仅提供结构化 AI 能力，不接触业务数据库。"""
import logging
import uuid
from typing import Any

from fastapi import Depends, FastAPI, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .config import get_settings
from .errors import AiError
from .llm import ModelOverride
from .prompts import load_prompt
from .schemas import ANALYSIS_SCHEMAS
from .service import AnalysisService, get_analysis_service

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("serviceflow.ai")

app = FastAPI(title="ServiceFlow AI", version="5.0.0")


class ProviderOverride(BaseModel):
    """Java 治理路由下发的供应商参数；只在本次请求内使用，不写日志、不回显。"""

    baseUrl: str = Field(min_length=8, max_length=255)
    apiKey: str = Field(min_length=1, max_length=512)
    model: str = Field(min_length=1, max_length=64)
    temperature: float = Field(default=0.2, ge=0, le=2)
    maxTokens: int = Field(default=2048, ge=1, le=32768)
    timeoutSeconds: float = Field(default=60, ge=1, le=600)


class AiRequest(BaseModel):
    requestId: str | None = Field(default=None, max_length=64)
    payload: dict[str, Any] = Field(default_factory=dict)
    provider: ProviderOverride | None = None


@app.exception_handler(AiError)
async def handle_ai_error(_request, exc: AiError) -> JSONResponse:
    request_id = getattr(exc, "request_id", "") or ""
    return JSONResponse(status_code=exc.status_code, content=exc.to_dict(request_id))


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "serviceflow-ai", "version": "5.0.0"}


@app.get("/health/ready")
def ready() -> JSONResponse:
    settings = get_settings()
    issues: list[str] = []
    if not settings.api_key and not settings.allow_provider_override:
        issues.append("DEEPSEEK_API_KEY 未配置")
    prompt_versions: list[str] = []
    for name in sorted({item[0] for item in ANALYSIS_SCHEMAS.values()}):
        try:
            prompt_versions.append(load_prompt(name).prompt_version)
        except Exception as exc:  # noqa: BLE001 - 启动期需要汇总所有 Prompt 问题
            issues.append(f"{name}: {exc}")
    if issues:
        return JSONResponse(
            status_code=503,
            content={"status": "DOWN", "model": settings.model, "issues": issues},
        )
    return JSONResponse(
        content={
            "status": "UP",
            "model": settings.model,
            "baseUrl": settings.base_url,
            "prompts": prompt_versions,
        }
    )


def trace_id(x_request_id: str | None = Header(default=None, alias="X-Request-Id")) -> str:
    """上游 Java 服务透传的追踪 ID；缺失时由 _run 生成。"""
    return (x_request_id or "").strip()


async def _run(analysis_type: str, body: AiRequest, service: AnalysisService, trace: str) -> dict:
    request_id = (body.requestId or "").strip() or trace or str(uuid.uuid4())
    # traceId 用于与 Java 访问日志关联；requestId 保留业务侧传入的分析标识
    logger.info("ai_request type=%s requestId=%s traceId=%s provider=%s",
                analysis_type, request_id, trace or request_id, "governed" if body.provider else "env")
    provider = None
    if body.provider is not None:
        provider = ModelOverride(
            api_key=body.provider.apiKey,
            base_url=body.provider.baseUrl,
            model=body.provider.model,
            temperature=body.provider.temperature,
            max_tokens=body.provider.maxTokens,
            timeout_seconds=body.provider.timeoutSeconds,
        )
    try:
        envelope = await service.run(analysis_type, body.payload, request_id, provider)
        logger.info("ai_success type=%s requestId=%s latencyMs=%s", analysis_type, request_id, envelope["latencyMs"])
        return envelope
    except AiError as exc:
        exc.request_id = request_id
        logger.warning(
            "ai_failure type=%s requestId=%s code=%s status=%s",
            analysis_type,
            request_id,
            exc.code,
            exc.status_code,
        )
        raise


@app.post("/ai/ticket-analysis")
async def ticket_analysis(
    body: AiRequest,
    service: AnalysisService = Depends(get_analysis_service),
    trace: str = Depends(trace_id),
) -> dict:
    return await _run("TICKET_ANALYSIS", body, service, trace)


@app.post("/ai/priority-recommendation")
async def priority_recommendation(
    body: AiRequest,
    service: AnalysisService = Depends(get_analysis_service),
    trace: str = Depends(trace_id),
) -> dict:
    return await _run("PRIORITY", body, service, trace)


@app.post("/ai/solution-generation")
async def solution_generation(
    body: AiRequest,
    service: AnalysisService = Depends(get_analysis_service),
    trace: str = Depends(trace_id),
) -> dict:
    return await _run("SOLUTION", body, service, trace)


@app.post("/ai/knowledge-recommendation")
async def knowledge_recommendation(
    body: AiRequest,
    service: AnalysisService = Depends(get_analysis_service),
    trace: str = Depends(trace_id),
) -> dict:
    return await _run("KNOWLEDGE", body, service, trace)


@app.post("/ai/dispatch-assistance")
async def dispatch_assistance(
    body: AiRequest,
    service: AnalysisService = Depends(get_analysis_service),
    trace: str = Depends(trace_id),
) -> dict:
    return await _run("DISPATCH", body, service, trace)


@app.post("/ai/ticket-qa")
async def ticket_qa(
    body: AiRequest,
    service: AnalysisService = Depends(get_analysis_service),
    trace: str = Depends(trace_id),
) -> dict:
    return await _run("CHAT", body, service, trace)

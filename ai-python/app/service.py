"""AI 分析编排：Prompt 渲染 → 模型调用 → JSON 解析 → Schema 校验 → 业务校验。"""
import json
import re
import time
from collections import deque
from typing import Any

from pydantic import ValidationError

from .config import get_settings
from .errors import AiError
from .llm import DeepSeekClient, ModelOverride
from .prompts import load_prompt
from .schemas import ANALYSIS_SCHEMAS, SLA_HOURS, StrictModel

TICKET_FIELDS = {
    "ticketNo": 32,
    "title": 200,
    "description": 4000,
    "type": 32,
    "priority": 16,
    "status": 16,
    "createdAt": 32,
    "assignee": 64,
    "creator": 64,
}

ANALYSIS_TYPE_ALIASES = {
    "TICKET_ANALYSIS": "TICKET_ANALYSIS",
    "PRIORITY": "PRIORITY",
    "SOLUTION": "SOLUTION",
    "KNOWLEDGE": "KNOWLEDGE",
    "DISPATCH": "DISPATCH",
    "CHAT": "CHAT",
}


class RateLimiter:
    """单实例进程内限流：窗口 60 秒。"""

    def __init__(self, limit: int) -> None:
        self.limit = limit
        self._events: deque[float] = deque()

    def check(self) -> None:
        now = time.monotonic()
        while self._events and now - self._events[0] > 60:
            self._events.popleft()
        if len(self._events) >= self.limit:
            raise AiError("AI_RATE_LIMITED", "AI 调用过于频繁，请稍后重试", status_code=429)
        self._events.append(now)


def clip(value: Any, limit: int) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\x00", "")
    return text[:limit]


def sanitize_ticket(raw: Any) -> dict[str, str]:
    if not isinstance(raw, dict):
        raise AiError("AI_BAD_REQUEST", "缺少工单上下文", status_code=400)
    ticket = {}
    for key, limit in TICKET_FIELDS.items():
        ticket[key] = clip(raw.get(key), limit)
    if not ticket["title"] or not ticket["description"]:
        raise AiError("AI_BAD_REQUEST", "工单标题与描述不能为空", status_code=400)
    return ticket


def parse_model_json(content: str) -> dict:
    text = content.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise AiError(
            "AI_INVALID_OUTPUT",
            "AI 返回内容不是合法 JSON",
            status_code=422,
            detail=f"JSON 解析失败（位置 {exc.pos}）",
        )
    if not isinstance(data, dict):
        raise AiError("AI_INVALID_OUTPUT", "AI 返回内容不是 JSON 对象", status_code=422)
    return data


def validate_schema(data: dict, schema: type[StrictModel]) -> StrictModel:
    try:
        return schema.model_validate(data)
    except ValidationError as exc:
        first = exc.errors()[0]
        location = ".".join(str(item) for item in first.get("loc", ())) or "root"
        raise AiError(
            "AI_INVALID_OUTPUT",
            "AI 返回字段不符合约定结构",
            status_code=422,
            detail=f"{location}: {first.get('msg', 'invalid')}",
        )


class AnalysisService:
    def __init__(self, client: DeepSeekClient | None = None, limiter: RateLimiter | None = None) -> None:
        self.client = client or DeepSeekClient()
        self.limiter = limiter or RateLimiter(get_settings().rate_limit_per_minute)

    async def run(
        self,
        analysis_type: str,
        payload: dict[str, Any],
        request_id: str,
        provider: ModelOverride | None = None,
    ) -> dict:
        normalized = ANALYSIS_TYPE_ALIASES.get((analysis_type or "").upper())
        if not normalized:
            raise AiError("AI_BAD_REQUEST", f"不支持的分析类型: {analysis_type}", status_code=400)

        prompt_name, schema, result_type = ANALYSIS_SCHEMAS[normalized]
        ticket = sanitize_ticket(payload.get("ticket"))
        template = load_prompt(prompt_name)
        variables = self._build_variables(normalized, payload, ticket)

        self.limiter.check()
        started = time.perf_counter()
        content, model, usage = await self.client.chat_json(
            template.system, template.render(variables), request_id, override=provider
        )
        latency_ms = int((time.perf_counter() - started) * 1000)

        data = parse_model_json(content)
        result = validate_schema(data, schema)
        self._validate_business(normalized, result, payload)

        return {
            "requestId": request_id,
            "type": result_type,
            "promptVersion": template.prompt_version,
            "model": model,
            "latencyMs": latency_ms,
            "usage": usage,
            "result": result.model_dump(),
        }

    def _build_variables(self, analysis_type: str, payload: dict, ticket: dict[str, str]) -> dict[str, str]:
        sla = payload.get("sla") if isinstance(payload.get("sla"), dict) else {}
        skills = payload.get("skills") if isinstance(payload.get("skills"), list) else []
        knowledge = payload.get("knowledge") if isinstance(payload.get("knowledge"), list) else []
        dispatch = payload.get("dispatch") if isinstance(payload.get("dispatch"), dict) else {}
        context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
        question = clip(payload.get("question"), 2000)

        if analysis_type == "CHAT" and not question:
            raise AiError("AI_BAD_REQUEST", "追问内容不能为空", status_code=400)
        if analysis_type == "DISPATCH" and not dispatch.get("candidates"):
            raise AiError("AI_BAD_REQUEST", "缺少规则调度候选，无法进行 AI 辅助", status_code=400)
        if analysis_type == "KNOWLEDGE" and not knowledge:
            raise AiError("AI_BAD_REQUEST", "缺少知识候选，无法推荐", status_code=400)

        return {
            "ticket_json": json.dumps(ticket, ensure_ascii=False),
            "sla_json": json.dumps(sla, ensure_ascii=False),
            "skills_json": json.dumps(skills, ensure_ascii=False),
            "knowledge_json": json.dumps(knowledge, ensure_ascii=False),
            "dispatch_json": json.dumps(dispatch, ensure_ascii=False),
            "context_json": json.dumps(context, ensure_ascii=False),
            "question": question,
        }

    def _validate_business(self, analysis_type: str, result: StrictModel, payload: dict) -> None:
        if analysis_type == "PRIORITY":
            expected = SLA_HOURS[result.recommendedPriority]  # type: ignore[attr-defined]
            if result.suggestedResponseHours != expected:  # type: ignore[attr-defined]
                raise AiError(
                    "AI_INVALID_OUTPUT",
                    "AI 建议的时限与既有 SLA 策略不一致",
                    status_code=422,
                    detail="suggestedResponseHours 与 recommendedPriority 不匹配",
                )
        elif analysis_type == "KNOWLEDGE":
            allowed = {
                int(item.get("articleId"))
                for item in payload.get("knowledge", [])
                if isinstance(item, dict) and item.get("articleId") is not None
            }
            for item in result.recommendations:  # type: ignore[attr-defined]
                if item.articleId not in allowed:
                    raise AiError(
                        "AI_INVALID_OUTPUT",
                        "AI 推荐了候选集合之外的知识",
                        status_code=422,
                        detail=f"articleId={item.articleId} 不在候选集合内",
                    )
        elif analysis_type == "DISPATCH":
            allowed = {
                int(item.get("agentId"))
                for item in (payload.get("dispatch") or {}).get("candidates", [])
                if isinstance(item, dict) and item.get("agentId") is not None
            }
            seen = set()
            for item in result.candidates:  # type: ignore[attr-defined]
                if item.agentId not in allowed or item.agentId in seen:
                    raise AiError(
                        "AI_INVALID_OUTPUT",
                        "AI 返回了非法的人员推荐",
                        status_code=422,
                        detail=f"agentId={item.agentId} 不在规则候选内或重复",
                    )
                seen.add(item.agentId)
            recommended = result.recommendedAgentId  # type: ignore[attr-defined]
            if recommended is not None and recommended not in allowed:
                raise AiError(
                    "AI_INVALID_OUTPUT",
                    "AI 推荐的最终人员不在候选集合内",
                    status_code=422,
                    detail=f"agentId={recommended}",
                )


_service: AnalysisService | None = None


def get_analysis_service() -> AnalysisService:
    """进程级单例，保证限流窗口在请求之间生效。"""
    global _service
    if _service is None:
        _service = AnalysisService()
    return _service

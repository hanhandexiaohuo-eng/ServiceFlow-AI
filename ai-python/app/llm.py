"""DeepSeek 调用封装：超时、有限重试、限流与错误码映射。

安全约束：
- 默认 API Key 只从环境变量读取；治理路由启用时由 Java 在请求内下发，只在内存中使用。
- 无论哪种来源，密钥都不写日志、不回传、不进入响应。
- 日志只记录状态码与错误类型，不记录 Authorization 与 Prompt 正文。
"""
import asyncio
import logging
import time
from dataclasses import dataclass

import httpx

from .config import Settings, get_settings
from .errors import AiError

logger = logging.getLogger("serviceflow.ai.llm")

RETRYABLE_STATUS = {429, 500, 502, 503, 504}


@dataclass(frozen=True)
class ModelOverride:
    """治理路由下发的供应商参数：仅在本次请求内有效，不落盘、不打印。"""

    api_key: str
    base_url: str
    model: str
    temperature: float
    max_tokens: int
    timeout_seconds: float


class DeepSeekClient:
    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings

    @property
    def settings(self) -> Settings:
        return self._settings or get_settings()

    async def chat_json(
        self,
        system: str,
        user: str,
        request_id: str,
        override: ModelOverride | None = None,
    ) -> tuple[str, str, dict]:
        settings = self.settings
        if override is not None:
            api_key = override.api_key
            base_url = override.base_url.rstrip("/")
            model = override.model
            temperature = override.temperature
            max_tokens = override.max_tokens
            timeout_seconds = override.timeout_seconds
        else:
            api_key = settings.api_key
            base_url = settings.base_url
            model = settings.model
            temperature = 0.2
            max_tokens = 2048
            timeout_seconds = settings.timeout_seconds

        if not api_key:
            raise AiError(
                "AI_CONFIG_MISSING",
                "AI 服务未配置模型密钥",
                status_code=503,
                detail="请在 AI 模型治理中配置供应商，或为 AI 服务进程设置 DEEPSEEK_API_KEY",
            )

        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
            "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        url = f"{base_url}/chat/completions"
        timeout = httpx.Timeout(timeout_seconds, connect=5.0)
        last_error: AiError | None = None

        for attempt in range(3):
            attempt_started = time.monotonic()
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(url, json=payload, headers=headers)
            except httpx.TimeoutException:
                last_error = AiError("AI_TIMEOUT", "模型响应超时", status_code=504, retryable=True)
                logger.warning(
                    "ai_call_timeout requestId=%s attempt=%s costMs=%s",
                    request_id,
                    attempt + 1,
                    int((time.monotonic() - attempt_started) * 1000),
                )
            except httpx.HTTPError as exc:
                last_error = AiError(
                    "AI_UNAVAILABLE",
                    "模型服务不可用",
                    status_code=503,
                    detail=type(exc).__name__,
                    retryable=True,
                )
                logger.warning(
                    "ai_call_error requestId=%s attempt=%s error=%s",
                    request_id,
                    attempt + 1,
                    type(exc).__name__,
                )
            else:
                cost_ms = int((time.monotonic() - attempt_started) * 1000)
                if response.status_code == 200:
                    logger.info(
                        "model_call requestId=%s attempt=%s costMs=%s status=SUCCESS",
                        request_id,
                        attempt + 1,
                        cost_ms,
                    )
                    content, usage = self._extract(response, request_id)
                    return content, model, usage
                if response.status_code in (401, 403):
                    raise AiError(
                        "AI_CONFIG_INVALID",
                        "模型鉴权失败，请检查供应商密钥",
                        status_code=503,
                    )
                if response.status_code == 429:
                    last_error = AiError(
                        "AI_RATE_LIMITED", "模型接口限流，请稍后重试", status_code=429, retryable=True
                    )
                elif response.status_code in RETRYABLE_STATUS:
                    last_error = AiError(
                        "AI_UPSTREAM_ERROR",
                        "模型服务返回异常",
                        status_code=502,
                        detail=f"HTTP {response.status_code}",
                        retryable=True,
                    )
                else:
                    raise AiError(
                        "AI_UPSTREAM_ERROR",
                        "模型请求被拒绝",
                        status_code=502,
                        detail=f"HTTP {response.status_code}",
                    )
                logger.warning(
                    "ai_call_status requestId=%s attempt=%s status=%s",
                    request_id,
                    attempt + 1,
                    response.status_code,
                )

            if attempt < 2:
                await asyncio.sleep(settings.retry_delay_seconds * (attempt + 1))

        assert last_error is not None
        raise last_error

    def _extract(self, response: httpx.Response, request_id: str) -> tuple[str, dict]:
        try:
            data = response.json()
            choices = data.get("choices") or []
            content = (choices[0].get("message") or {}).get("content")
        except (ValueError, AttributeError, IndexError):
            raise AiError("AI_INVALID_OUTPUT", "模型响应结构异常", status_code=422)
        if not isinstance(content, str) or not content.strip():
            logger.warning("ai_empty_content requestId=%s", request_id)
            raise AiError("AI_EMPTY_RESPONSE", "模型返回空内容", status_code=422, retryable=True)
        raw_usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
        usage = {
            "promptTokens": raw_usage.get("prompt_tokens"),
            "completionTokens": raw_usage.get("completion_tokens"),
            "totalTokens": raw_usage.get("total_tokens"),
        }
        return content, usage

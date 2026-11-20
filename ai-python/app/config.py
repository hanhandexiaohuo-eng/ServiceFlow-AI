"""AI 服务配置：全部来自环境变量，密钥不落盘、不打印。"""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    api_key: str
    base_url: str
    model: str
    timeout_seconds: float
    rate_limit_per_minute: int
    retry_delay_seconds: float
    # 治理路由启用后，Java 会在每次请求中下发供应商参数；
    # 此时进程内可以不配置 DEEPSEEK_API_KEY，健康检查不应因此报 DOWN。
    allow_provider_override: bool = False


def get_settings() -> Settings:
    return Settings(
        api_key=(os.getenv("DEEPSEEK_API_KEY") or "").strip(),
        base_url=(os.getenv("DEEPSEEK_BASE_URL") or "https://api.deepseek.com").rstrip("/"),
        model=(os.getenv("DEEPSEEK_MODEL") or "deepseek-chat").strip(),
        timeout_seconds=float(os.getenv("DEEPSEEK_TIMEOUT_SECONDS") or "60"),
        rate_limit_per_minute=int(os.getenv("AI_RATE_LIMIT_PER_MINUTE") or "12"),
        retry_delay_seconds=float(os.getenv("AI_RETRY_DELAY_SECONDS") or "0.5"),
        allow_provider_override=(os.getenv("AI_ALLOW_PROVIDER_OVERRIDE") or "").strip().lower()
        in {"1", "true", "yes", "on"},
    )

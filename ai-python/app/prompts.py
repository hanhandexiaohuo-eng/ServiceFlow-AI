"""Prompt 加载与渲染：按类型/版本从 prompts 目录读取，模板与代码分离。"""
import re
from dataclasses import dataclass
from pathlib import Path

PROMPT_ROOT = Path(__file__).resolve().parent.parent / "prompts"

# 允许出现的占位符，出现未注册占位符时启动即失败，避免静默拼接错误
ALLOWED_PLACEHOLDERS = {
    "ticket_json",
    "sla_json",
    "skills_json",
    "knowledge_json",
    "dispatch_json",
    "context_json",
    "question",
}

_cache: dict[tuple[str, str], "PromptTemplate"] = {}


@dataclass(frozen=True)
class PromptTemplate:
    name: str
    version: str
    system: str
    user_template: str

    @property
    def prompt_version(self) -> str:
        return f"{self.name}@{self.version}"

    def render(self, variables: dict[str, str]) -> str:
        rendered = self.user_template
        for key, value in variables.items():
            if key not in ALLOWED_PLACEHOLDERS:
                continue
            rendered = rendered.replace("{{" + key + "}}", value)
        if "{{" in rendered:
            unresolved = rendered[rendered.index("{{"): rendered.index("{{") + 40]
            raise ValueError(f"Prompt {self.prompt_version} 存在未解析占位符: {unresolved}")
        return rendered


def load_prompt(name: str, version: str = "v1") -> PromptTemplate:
    key = (name, version)
    cached = _cache.get(key)
    if cached:
        return cached

    path = PROMPT_ROOT / name / f"{version}.md"
    if not path.exists():
        raise FileNotFoundError(f"Prompt 文件不存在: {path}")
    text = path.read_text(encoding="utf-8")

    meta: dict[str, str] = {}
    body = text
    if text.startswith("---"):
        parts = text.split("---", 2)
        if len(parts) < 3:
            raise ValueError(f"Prompt 头部格式错误: {path}")
        for line in parts[1].strip().splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                meta[k.strip()] = v.strip()
        body = parts[2]

    if "[SYSTEM]" not in body or "[USER]" not in body:
        raise ValueError(f"Prompt 缺少 [SYSTEM] 或 [USER] 段: {path}")
    system_part, user_part = body.split("[SYSTEM]", 1)[1].split("[USER]", 1)

    template = PromptTemplate(
        name=meta.get("type") or name,
        version=meta.get("version") or version,
        system=system_part.strip(),
        user_template=user_part.strip(),
    )

    used = set(re.findall(r"\{\{(\w+)\}\}", template.user_template))
    unknown = used - ALLOWED_PLACEHOLDERS
    if unknown:
        raise ValueError(f"Prompt {template.prompt_version} 使用了未注册占位符: {sorted(unknown)}")
    _cache[key] = template
    return template

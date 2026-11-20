#!/usr/bin/env python3
"""敏感信息扫描：源码、配置、文档、构建产物统一检查。

设计目标：
- 零第三方依赖，本地与 CI 使用同一份规则，避免"本地过、CI 挂"。
- 只报告位置与规则名，不打印命中的密钥内容，避免扫描日志变成新的泄露渠道。
- 命中任一规则即以非 0 退出，CI 据此阻断构建。

支持的例外：
- 行尾注释 `secret-scan: allow`（必须写明原因，例如弱口令黑名单、占位符示例）。
- `ALLOWLIST_FILES` 中列出的文件（仅限规则性质上会被误判的代码）。
- 明显的占位符值（`<...>`、`${...}`、`replace_*`、`your_*`、含 test/example/dummy 等）。
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

# 占位符 / 示例值的特征，命中后跳过（不视为真实凭据）
PLACEHOLDER_HINTS = (
    "replace_", "your_", "your-", "example", "placeholder", "changeme",
    "change_me", "dummy", "fake", "test-key", "test_key", "unit-test",
    "not-used", "xxxx", "****",
)

ALLOWLIST_FILES = {
    # 弱口令黑名单：这里出现的是"被禁止使用"的密码，不是可用凭据
    "backend-java/src/main/java/com/serviceflow/ai/auth/security/PasswordPolicy.java",
}

SKIP_DIRS = {".git", "node_modules", ".venv", "__pycache__", ".idea", ".vscode", ".mvn"}
SKIP_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz",
    ".jar", ".class", ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".lock",
}
MAX_FILE_BYTES = 2 * 1024 * 1024


@dataclass(frozen=True)
class Rule:
    name: str
    pattern: re.Pattern[str]
    # 命中后对"值"做占位符判断的捕获组序号；None 表示整行按字面量判定
    value_group: int | None = None


RULES: tuple[Rule, ...] = (
    Rule("private-key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    Rule("aws-access-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    Rule("model-api-key", re.compile(r"\bsk-[A-Za-z0-9]{20,}\b")),
    Rule(
        "jwt-literal",
        re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
    ),
    # 项目历史默认凭据：出现即视为回归
    Rule("known-default-credential", re.compile(r"(?i)\b(?:admin\s*/\s*123456|bob\s*/\s*user123)\b")),
    # 疑似"键 = 明文值"赋值；值必须带引号且长度足够，减少噪声
    Rule(
        "assigned-secret",
        re.compile(
            r"(?i)\b(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)"
            r"\b\s*[:=]\s*['\"]([^'\"\s]{6,})['\"]"
        ),
        value_group=1,
    ),
)


def is_placeholder(value: str) -> bool:
    lowered = value.lower()
    if any(hint in lowered for hint in PLACEHOLDER_HINTS):
        return True
    stripped = value.strip()
    return (
        stripped.startswith("<")
        or stripped.startswith("${")
        or stripped.startswith("{{")
        or stripped.startswith("$")
    )


def iter_files(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = Path(dirpath) / name
            if path.suffix.lower() in SKIP_SUFFIXES:
                continue
            try:
                if path.stat().st_size > MAX_FILE_BYTES:
                    continue
            except OSError:
                continue
            yield path


def relative(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def scan(root: Path) -> list[tuple[str, int, str]]:
    findings: list[tuple[str, int, str]] = []
    for path in iter_files(root):
        rel = relative(root, path)
        if rel in ALLOWLIST_FILES:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if not text:
            continue
        for lineno, line in enumerate(text.splitlines(), start=1):
            if "secret-scan: allow" in line:
                continue
            for rule in RULES:
                match = rule.pattern.search(line)
                if not match:
                    continue
                if rule.value_group is not None and is_placeholder(match.group(rule.value_group)):
                    continue
                findings.append((rel, lineno, rule.name))
                break
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="扫描敏感信息，命中即失败")
    parser.add_argument("--root", default=".", help="扫描根目录")
    parser.add_argument("--quiet", action="store_true", help="只输出汇总")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    if not root.exists():
        print(f"扫描根目录不存在：{root}", file=sys.stderr)
        return 2

    findings = scan(root)
    if not findings:
        print(f"敏感信息扫描通过：未发现凭据、Token 或私钥（根目录 {root}）")
        return 0

    print(f"敏感信息扫描失败：发现 {len(findings)} 处疑似敏感信息（仅显示位置与规则，不显示内容）")
    if not args.quiet:
        for rel, lineno, rule in findings:
            print(f"  - {rel}:{lineno}  [{rule}]")
    print("请移除明文凭据，改用环境变量/密钥管理，或在确认误报后添加 `secret-scan: allow` 注释。")
    return 1


if __name__ == "__main__":
    sys.exit(main())

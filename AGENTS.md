# AGENTS.md — ServiceFlow AI 项目约束

本文件面向参与本项目的 AI 代理与开发者，定义架构边界、编码规范、测试与密钥安全要求。任何改动都不得违反以下约束。

## 1. 架构约束

- 技术栈固定为：Vue 3 + Vite + TypeScript（frontend）、Java 17 + Spring Boot 3.x + Maven（backend-java）、Python 3.11+ + FastAPI（ai-python）、MySQL 8.x（deploy）。
- 目录职责单一，禁止跨层直连：
  - 前端只通过 `/api` 前缀访问 Java 后端；开发环境由 Vite 代理转发，不在前端硬编码后端域名。
  - Java 后端是唯一对外业务入口，负责数据库读写与调度；前端不直连数据库、不直连 Python 服务。
  - Python AI 服务仅提供 AI 能力（分类、信息提取、建议生成），不直接写业务数据库。
- 端口约定：前端 5173、Java 8080、Python 8000、MySQL 3306（仅本机/容器内）。
- 当前处于第一阶段，仅包含健康检查与骨架。未完成的登录鉴权、Redis、消息队列、权限模型、AI 工作流不得提前实现或预留半成品代码。
- 新增第三方依赖必须说明用途；禁止为“可能用到”引入框架。

## 2. 代码规范

- Java：包结构 `com.serviceflow.ai.<领域>.<层次>`（controller / service / dto / config）。使用构造器注入，不使用字段注入；控制器只做参数校验与响应组装，业务逻辑放 service；对外响应使用 DTO（record），不直接返回实体。
- 数据库访问使用 Spring 提供的 JdbcTemplate 或后续引入的持久层，禁止拼接 SQL 字符串。
- 前端：组合式 API + `<script setup lang="ts">`；API 调用统一放在 `src/api/`，组件内不直接写 `fetch`；错误必须向用户展示可读信息。
- Python：FastAPI 路由保持薄，响应使用 Pydantic 模型；配置从环境变量读取并集中管理。
- 统一使用 UTF-8，注释只解释“为什么”，不复述代码。命名遵循各语言社区惯例，禁止中英混杂命名。

## 3. 测试要求

- Java：新增接口需有对应测试；无外部依赖的逻辑用纯单元测试（MockMvc standalone），禁止让单元测试依赖真实 MySQL。
- 前端：构建（`npm run build`，含类型检查）必须通过；关键交互需在浏览器中人工或自动化验证。
- Python：接口需可通过 `curl`/OpenAPI 手工验证；后续引入 pytest 后再补自动化测试。
- 任何验证结果必须来自真实执行，禁止伪造“已通过”。

## 4. 密钥与配置安全

- 数据库密码、DeepSeek API Key 等敏感信息只能来自环境变量或本地 `.env` 文件，禁止写入源码、文档、示例文件和提交记录。
- `.env.example` 只能包含占位符；真实 `.env` 已被 `.gitignore` 忽略。
- 日志中不得输出密码、Token、完整连接串中的凭据部分。
- 提交前确认 `git status` 不包含 `.env`、证书或密钥文件。

## 5. 交付纪律

- 一次只推进一个可验证的子任务，先运行、再扩展。
- 环境不支持的验收项必须如实记录到 `docs/phase-1-acceptance.md`，不得跳过或降低标准。

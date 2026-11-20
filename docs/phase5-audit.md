# Phase 5 审计报告：AI 智能工单处理中心

审计日期：2026-11-16
审计范围：`frontend`、`backend-java`、`ai-python`、`deploy`、现有运行库 `serviceflow`
审计方式：读取源码与迁移脚本 + 连接真实 MySQL 查询表结构与数据 + 检查本机 Python/环境变量/端口。

---

## 1. 结论摘要

| 项目 | 现状 | 对 Phase 5 的影响 |
| --- | --- | --- |
| 认证与接口保护 | 已完成。JWT + 拦截器，`/api/**` 除登录与健康检查外全部鉴权 | AI 接口可直接复用，无需绕过认证 |
| 工单 / 工作流 / SLA | 已完成并有 97 个后端测试 | AI 只做建议，采纳后调用现有业务接口 |
| 规则调度 | 已完成（Phase 4），推荐与分配分离 | AI 调度辅助复用 `DispatchService.recommend`，不新增第二套分配逻辑 |
| 知识库 | **不存在**，无任何知识表 | 需新建轻量知识库（关键词检索，非向量检索） |
| AI 会话 / 分析 / 建议 | **不存在** | 需新建 4 张 AI 业务表 |
| Python AI 服务 | 仅有 `/health`、`/health/ready` 骨架，无 DeepSeek 调用 | 需在现有服务上扩展，不另起服务 |
| DeepSeek 密钥 | 本机环境变量 `DEEPSEEK_API_KEY` 存在（36 字符），但格式不是常见的 `sk-` 前缀，仓库内无任何相关配置 | 真实联调存在失败风险，必须实测并如实记录 |
| Python 依赖 | 全局 Python 3.11.9 未安装 fastapi / uvicorn / pytest（httpx、pydantic 已装） | 需安装依赖后才能启动与测试 |
| 前端 AI 入口 | 菜单三项均为 `soon`，落到 `ComingSoonView` | 需替换为真实页面 |
| Java 版本 | `pom.xml` 为 `java.version=17`（任务描述为 Java 21） | 按现状 17 编译，不改构建基线 |

**总体判断**：Authentication、工单状态机、SLA、规则调度均可复用；Phase 5 属于**纯增量**：新增 AI 编排层（Java）、AI 能力层（Python）、知识库与会话/分析/建议持久化，以及 4 个前端页面。没有发现需要推翻重构的阻塞问题。

---

## 2. 目录与分层

```
serviceflow-ai/
├─ backend-java/   Spring Boot 3.5.16，JdbcTemplate，包结构 com.serviceflow.ai.<domain>.<layer>
├─ ai-python/      FastAPI，当前仅 app/main.py + requirements.txt
├─ frontend/       Vue 3 + TS strict + Vite + hash router
├─ deploy/         init/*.sql（全量初始化）、migrations/*.sql（增量迁移）
└─ docs/           phase3 / phase4 设计与验收文档
```

后端已有领域包：`auth`、`ticket`、`dispatch`、`health`、`common`。新增 Phase 5 代码应放入新的 `intelligence`（AI 编排）与 `knowledge`（知识库）包，不修改既有分层约定。

---

## 3. 认证与权限现状

- [AuthInterceptor.java](../backend-java/src/main/java/com/serviceflow/ai/auth/interceptor/AuthInterceptor.java)：校验 Bearer Token → 查库确认用户存在且 `status=1` → 写入 `UserContext`；角色以数据库为准。
- [WebMvcConfig.java](../backend-java/src/main/java/com/serviceflow/ai/common/config/WebMvcConfig.java)：拦截 `/api/**`，仅排除 `/api/auth/login`、`/api/health/**`。
- 角色：`ADMIN` / `AGENT` / `USER`。
- 统一响应：`ApiResponse{code,message,data}`；异常映射 400/401/403/404/409/500，见 [GlobalExceptionHandler.java](../backend-java/src/main/java/com/serviceflow/ai/common/exception/GlobalExceptionHandler.java)。
- 前端：Token 存 `localStorage`，`request.ts` 统一加 `Authorization`，401 自动清理并跳登录页。

**审计发现（重要）**：现有工单读接口（列表/详情/事件）对**任意已登录用户**开放，没有"仅创建人 / 处理人可看"的限制。Phase 5 的 AI 会话明确要求"普通用户不能查看其他用户不应访问的会话和工单数据"，因此：

- 不改动已有工单接口行为（避免破坏前四阶段）；
- 在 AI / 知识库模块内新增 `AiTicketAccessGuard`：`ADMIN` 全量；`USER` 仅本人工单；`AGENT` 仅分配给本人的工单；
- 该差异会写入设计文档与测试报告，作为已知边界。

---

## 4. 工单 / 工作流 / SLA 现状

- 工单表 `tickets`：7 状态 `PENDING/ASSIGNED/ACCEPTED/PROCESSING/RESOLVED/CLOSED/CANCELLED`；类型 `INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT`；优先级 `LOW/MEDIUM/HIGH/URGENT`。
- 状态机唯一入口：`TicketWorkflowService` → `TicketWorkflowEngine`，条件更新 `WHERE status = 旧值` 做并发保护（冲突 409）。
- `TicketService.update` 只允许改内容字段；提交 `status/assignee` 会被显式拒绝；修改优先级会通过 `TicketSlaService.dueAt` 重算 SLA。
- SLA 策略：URGENT 4h / HIGH 8h / MEDIUM 24h / LOW 72h，预警阈值 120 分钟；`TicketSlaService.evaluate` 返回 `ON_TRACK/WARNING/OVERDUE/COMPLETED`、剩余分钟与已消耗百分比。
- 真实数据：`tickets` 21 条，状态覆盖 7 种；存在 URGENT 未结工单 `SF20261116-YWEF`（勒索病毒告警，ASSIGNED），适合做 AI 分析联调样本。

**Phase 5 约束落地方式**：
- AI 优先级建议只写入 `ai_suggestions.payload`，不写 `tickets.priority`；
- 人工采纳后走 `TicketService.update`（内部重算 SLA、写 UPDATE 事件），不直接 UPDATE 数据库；
- 方案建议采纳不改变工单状态；状态推进仍由用户在详情页走工作流接口。

---

## 5. 规则调度现状（Phase 4）

- `DispatchService.recommend(ticketId, limit)`：仅 PENDING；候选过滤（账号启用、AGENT、AVAILABLE、必需技能、未满负载）；四维评分（技能 40 / 负载 30 / SLA 20 / 绩效 10）；返回候选、分项、原因、警告与排除原因；写 `dispatch_logs` RECOMMEND。
- `DispatchService.assign`：重新过滤 → `TicketWorkflowService.assign` → 写 ASSIGN 日志。
- 权限：推荐/确认/工单池/历史仅 ADMIN；人员列表与负载 ADMIN/AGENT。
- 真实数据：5 个用户，3 个 AGENT（赵一/钱二/孙三）均有技能与并发上限，可直接作为 AI 调度辅助输入。

**Phase 5 约束落地方式**：AI 调度辅助只调用 `DispatchService.recommend` 拿到规则结果，再把候选交给 Python 做"优势/不足/风险"说明；不新增候选，不直接调用 `assign`。最终分配仍由管理员在调度工作台调用 `POST /api/dispatch/assign`。

---

## 6. 数据库现状

运行库 `serviceflow` 现有表：

| 表 | 用途 | Phase 5 是否复用 |
| --- | --- | --- |
| `users` | 账号、角色、状态 | 复用（会话/分析/建议的发起人外键） |
| `tickets` | 工单主数据 | 复用（AI 上下文与建议对象） |
| `ticket_events` | 流转事件 | 只读展示，不写 AI 事件 |
| `agent_profiles` / `agent_skills` / `agent_skill_relations` / `ticket_type_skill_rules` | 人员、技能、类型技能规则 | 复用（调度辅助） |
| `dispatch_logs` | 调度日志（含 JSON 快照） | 复用，不重复建表 |
| `tickets_phase2_backup` | 历史备份 | 不动 |

**确认不存在**：知识库、AI 会话、AI 消息、AI 分析、AI 建议相关表；`tickets` 无 resolution 之外的"解决方案库"字段。

迁移规范：`deploy/init/NN-*.sql` 全量初始化 + `deploy/migrations/YYYYMMDD-*.sql` 增量；均要求可重复执行、不破坏已有数据；时间字段 `DATETIME DEFAULT CURRENT_TIMESTAMP`；主键 `BIGINT UNSIGNED AUTO_INCREMENT`。

---

## 7. Python AI 服务现状

- 唯一文件 [ai-python/app/main.py](../ai-python/app/main.py)：FastAPI + 两个健康检查端点，注释明确"接入 DeepSeek 后在此增加真实检查"。
- 依赖文件仅 `fastapi`、`uvicorn`、`pydantic`；**缺少** `httpx`（调用模型）、`pytest`（测试）。
- 无 Prompt 目录、无模型客户端、无 JSON Schema 校验、无重试/限流、无 API Key 读取逻辑。
- 端口约定 8000，当前未被占用（可启动）。
- 本机 `DEEPSEEK_API_KEY` 已设置（长度 36，非 `sk-` 前缀，仓库内无来源记录）；无法确认有效性，必须在真实联调阶段实测。

**结论**：在现有 FastAPI 应用上扩展，不新建第三个服务；新增 `config / llm / prompts / schemas / routes / tests` 子模块，保持路由薄。

---

## 8. 前端现状

- 设计令牌集中在 `src/style.css`：深色侧边栏、浅色工作区、白卡、`--brand #4f46e5`、统一 `.btn/.card/.field` 风格。
- 通用组件：`DataTable`、`EmptyState`、`LoadingState`、`PriorityTag`、`SlaBadge`、`StatusTag`、`StatCard`、`AppIcon`（内置 SVG，新增图标需在 `PATHS` 注册）。
- 已有真实页面 12 个；AI 相关菜单 3 项均为 `soon: true`，由 `ComingSoonView` 承接。
- API 层规范：统一 `request<T>()`，组件内不写 `fetch`；错误 `throw new Error(message)` 由页面展示。
- 菜单与路由：`constants/menu.ts` + `router/index.ts` 双处维护（soon 页面由菜单自动生成路由）。

**Phase 5 需新增**：
- `api/ai.ts`、`api/knowledge.ts`、`constants/ai.ts`；
- `AiWorkspaceView`（三栏）、`AiAnalysisDetailView`、`KnowledgeBaseView`、`AiHistoryView`；
- 菜单把 AI 三项替换为真实入口，`知识库 > 知识文章` 指向 `/knowledge`；
- 复用现有卡片/标签/空状态/加载态，不引入 UI 框架。

---

## 9. 现有问题与风险清单

| # | 问题 | 影响 | 处理策略 |
| --- | --- | --- | --- |
| R1 | 工单读接口无逐单权限 | 普通用户可读他人工单，AI 会话若直接复用会越权 | AI 模块内新增访问守卫，会话/分析/知识关联全部校验；不改既有工单接口 |
| R2 | Python 依赖缺失（fastapi/uvicorn/pytest） | 无法启动与测试 | 先安装依赖，再开发 |
| R3 | `DEEPSEEK_API_KEY` 格式可疑且无仓库来源 | 真实联调可能失败 | 先做一次最小真实调用；失败则在测试报告"受外部配置影响/未通过"中如实记录，不用 Mock 冒充 |
| R4 | 无分布式限流/缓存 | 多实例部署时频率控制失效 | 单实例进程内限流（每分钟上限）+ 前端防重复点击；文档标注适用边界 |
| R5 | 模型输出不可信 | 可能污染数据库 | Python 端 Pydantic 严格校验（`extra=forbid`）+ Java 端二次字段白名单校验，失败不落业务表 |
| R6 | Prompt 注入（工单描述/知识内容） | 可能诱导泄露或越权 | 系统指令固定 + 不可信数据用定界标签包裹 + 输出仅 JSON + AI 无工具/无 SQL/无写库权限 |
| R7 | 长文本导致成本与时延 | 费用与超时 | 描述截断（4000 字符）、知识候选限 5 条、超时 60s、可恢复错误最多重试 2 次 |
| R8 | `pom.xml` Java 17 与任务描述 Java 21 不一致 | 低 | 保持现有 17 基线，不主动升级 |
| R9 | 现有 `dispatch_logs.detail` 使用 JSON 列 | 低 | Phase 5 分析与建议同样使用 JSON 列，沿用 MySQL 8 JSON 能力 |
| R10 | 现有日志写入 `logs/serviceflow-backend.log` | 密钥泄露风险 | AI 客户端只记录 requestId/类型/耗时/状态/错误码，不记录 Key、Authorization、完整 Prompt 或正文 |

---

## 10. 可复用的既有能力（明确清单）

- 认证：`UserContext`、`LoginUser`、`ForbiddenException`、`UnauthorizedException`。
- 工单读取与响应：`TicketRepository`、`TicketService.getById/toResponse`、`TicketSlaService.evaluate`。
- 业务写入：`TicketService.update`（分类/优先级采纳）、`TicketWorkflowService.assign`（调度确认）。
- 规则调度：`DispatchService.recommend`、`DispatchCandidateResponse`、`DispatchRecommendationResponse`。
- 序列化：Spring 自带 Jackson `ObjectMapper`（`dispatch_logs` 已在用）。
- 前端：`request`、`DataTable`、`EmptyState`、`LoadingState`、`PriorityTag`、`SlaBadge`、`AppIcon`、全局设计令牌。

**不复用/不重写**：状态机、SLA 计算、评分算法、权限拦截器、人员负载计算。

---

## 11. 审计结论

1. 前四阶段功能完整，Phase 5 无需改表结构，只需新增 5 张 AI/知识库表。
2. 认证、工单、SLA、调度均有稳定复用点；AI 只能"建议 + 人工确认 + 调用既有业务接口"。
3. 知识库为空，必须新建；第一版采用 MySQL 关键词检索，**不实现也不宣称向量检索/RAG**。
4. Python 服务为骨架，需扩展；DeepSeek 真实可用性未验证，是当前最大外部风险。
5. 前端 AI 入口是占位页，需新建 4 个真实页面并接入菜单。

下一步：输出 `docs/phase5-design.md`（数据库/API/权限/异常/交互）、`docs/phase5-prompt-and-schema.md`（Prompt 与结构化输出），然后按模块实施。

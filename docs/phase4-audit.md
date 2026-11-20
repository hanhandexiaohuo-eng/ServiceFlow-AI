# Phase 4 审计：智能调度引擎 + 人员负载管理

审计时间：2026-11-16
审计方式：逐文件阅读后端源码、建表/迁移 SQL、前端路由与 API 封装，不依赖文件名推断。

## 1. 用户模块现状

### 1.1 表结构（deploy/init/03-users.sql）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED PK | 用户主键 |
| username | VARCHAR(32) UNIQUE | 登录名 |
| password | VARCHAR(72) | BCrypt 哈希，接口从不返回 |
| name | VARCHAR(64) | 显示名，分配时用于解析处理人 |
| role | VARCHAR(16) | `ADMIN` / `AGENT` / `USER` |
| status | TINYINT | 1 启用，0 禁用 |
| created_at / updated_at | DATETIME | 时间戳 |

### 1.2 角色与账号事实

- `UserRole` 枚举已预留 `AGENT`，注释写明「为后续智能调度预留」，但**当前数据库没有任何 AGENT 账号**（仅有 admin/ADMIN、bob/USER）。
- 认证拦截器每个请求按 `userId` 查库，`status != 1` 直接 401；角色以数据库为准，不信任 JWT 旧 claim。
- `UserContext` 以 ThreadLocal 保存 `LoginUser(id, username, name, role)`。
- `GET /api/users` 返回**全部启用用户**（含 USER），供分配/转交下拉使用，当前不区分角色。
- 不存在注册、用户新增、角色修改接口；用户只能由初始化脚本/种子逻辑创建。

### 1.3 结论

- `AGENT` 是工单处理人员的预留角色，但从未落地。
- 普通 `USER` 目前**可以**成为处理人（见第 2 节）。
- 账号启用状态（`users.status`）与人员工作状态（可接单/繁忙/离线/禁用）没有任何区分，需要新增独立模型。

## 2. 工单分配逻辑现状

### 2.1 写入口

所有状态变更只允许通过 `TicketWorkflowService`：

- `assign(id, assigneeName)`：`TicketAction.ASSIGN`，仅 `PENDING → ASSIGNED`。
- `transfer(id, assigneeName)`：`ASSIGNED/ACCEPTED/PROCESSING → ASSIGNED`。
- 其余动作：accept/start/resolve/close/cancel。

`assign/transfer` 均调用 `UserQueryService.requireActiveByName`：

1. 按 `name` 或 `username` 匹配启用用户（`status = 1`）；
2. 同名多人直接拒绝；
3. 不校验角色，因此 USER 也能被分配。

执行层先经 `TicketWorkflowEngine.assertAllowed` 校验权限：

- `ASSIGN` / `CLOSE` / `CANCEL`：ADMIN 或工单创建人；
- `TRANSFER` / `ACCEPT` / `START` / `RESOLVE`：ADMIN 或当前处理人。

数据库更新使用 `UPDATE ... WHERE id = ? AND status = 期望旧值`，影响行数为 0 时返回 409，天然防并发重复分配；同时写入 `ticket_events`。

### 2.2 创建工单时指定处理人

`TicketService.create` 若请求带 `assignee`：

- 调用同一个 `requireActiveByName`；
- 直接落库为 `ASSIGNED`；
- 同样不校验角色。

### 2.3 结论

- 已有分配接口完整、并发安全、可审计，Phase 4 **必须复用**，不得新写 UPDATE 绕过状态机。
- 现状的缺陷是「任何启用用户都能接单」。Phase 4 需要把**新分配**收紧为 `ADMIN/AGENT`，并让调度引擎只推荐 `AGENT`。
- 历史工单的 `assignee_id` 已存在并已回填，可直接用于负载与绩效统计。

## 3. 工单状态机与 SLA 现状

### 3.1 状态（ticket/enums/TicketStatus.java）

`PENDING / ASSIGNED / ACCEPTED / PROCESSING / RESOLVED / CLOSED / CANCELLED`

流转：

| 动作 | 允许的起始状态 | 目标状态 |
|---|---|---|
| ASSIGN | PENDING | ASSIGNED |
| TRANSFER | ASSIGNED / ACCEPTED / PROCESSING | ASSIGNED |
| ACCEPT | ASSIGNED | ACCEPTED |
| START | ACCEPTED | PROCESSING |
| RESOLVE | PROCESSING | RESOLVED |
| CLOSE | RESOLVED | CLOSED |
| CANCEL | PENDING / ASSIGNED / ACCEPTED / PROCESSING | CANCELLED |

占用处理能力的有效状态：`ASSIGNED / ACCEPTED / PROCESSING`；`RESOLVED / CLOSED / CANCELLED` 释放。这与 Phase 4 负载口径一致，可直接采用。

### 3.2 SLA（ticket/service/TicketSlaService.java）

- 响应时限：URGENT 4h / HIGH 8h / MEDIUM 24h / LOW 72h。
- 预警阈值：剩余 ≤ 120 分钟。
- `evaluate(ticket)` 返回：`state`（`ON_TRACK/WARNING/OVERDUE/COMPLETED`）、`remainingMinutes`、`elapsedPercent`、`dueAt`、`completedAt`。
- 截止时间已持久化到 `tickets.sla_due_at`，前端不再自行推算。

### 3.3 结论

调度引擎的 SLA 紧迫度**必须直接调用 `TicketSlaService.evaluate`**，不得另写一套时限计算。

## 4. 当前数据库表

| 表 | 作用 | 与 Phase 4 的关系 |
|---|---|---|
| users | 账号/角色/启用状态 | 复用，作为人员主体 |
| tickets | 工单，含 `assignee_id`、`sla_due_at`、各终态时间 | 复用，负载/绩效/调度对象 |
| ticket_events | 工单流转审计 | 复用，分配事件仍由工作流写 |
| tickets_phase2_backup | Phase 3 迁移快照 | 只读，不涉及 |

**当前不存在**：人员档案、技能、技能关联、工单类型—技能规则、调度日志、负载快照等任何表。

## 5. 前端现状

- 路由与菜单：`frontend/src/constants/menu.ts` 中「智能调度」下两项（`/dispatch`、`/dispatch/workload`）均标记 `soon: true`，实际指向 `ComingSoonView`。
- 工单分配抽屉 `components/AssignDrawer.vue` 使用 `listUsers()`（`GET /api/users`），候选包含 USER。
- `constants/ticket.ts` 的 `ROLE_LABEL` 只有 ADMIN/USER，没有 AGENT。
- 不存在人员管理、负载、调度相关页面或 API 封装。

## 6. 缺失的数据模型与服务

### 6.1 缺失表

- `agent_profiles`：最大并发、人员工作状态。
- `agent_skills`：技能字典。
- `agent_skill_relations`：人员—技能—熟练度。
- `ticket_type_skill_rules`：工单类型 → 所需技能（数据驱动，避免标题字符串匹配）。
- `dispatch_logs`：推荐/确认分配快照与历史。

### 6.2 缺失服务

- 人员档案与技能管理（含角色提升与权限边界）。
- 实时负载统计（占用、负载率、今日/本周完成、平均时长、SLA 达成率）。
- 候选过滤、四维评分、可解释推荐。
- 确认分配落库与调度日志。

## 7. 可复用清单

| 能力 | 复用点 |
|---|---|
| 登录与鉴权 | `AuthInterceptor`、`UserContext`、`UserRepository` |
| 用户解析 | `UserQueryService.requireActiveByName` |
| 状态机 | `TicketWorkflowEngine`、`TicketWorkflowService.assign` |
| SLA | `TicketSlaService.evaluate` |
| 工单查询 | `TicketRepository.findPage/count`、`TicketService.page/toResponse` |
| 分页响应 | `PageResponse`、`ApiResponse` |
| 异常 | `ForbiddenException / NotFoundException / ConflictException` |
| 前端页面骨架 | `DataTable`、`StatCard`、`SlaBadge`、`PriorityTag`、`AppIcon` |

## 8. 需要新增/修改的文件

### 8.1 新增（后端，`com.serviceflow.ai.dispatch`）

```
config/DispatchProperties.java
enums/AgentAvailability.java
enums/LoadLevel.java
enums/DispatchAction.java
entity/AgentProfile.java
entity/AgentSkill.java
entity/AgentSkillRelation.java
entity/TicketTypeSkillRule.java
entity/DispatchLog.java
dto/AgentCreateRequest.java
dto/AgentUpdateRequest.java
dto/AgentSkillItem.java
dto/AgentSkillsRequest.java
dto/AgentAvailabilityRequest.java
dto/SkillCreateRequest.java
dto/DispatchAssignRequest.java
vo/AgentResponse.java
vo/AgentSkillResponse.java
vo/SkillResponse.java
vo/WorkloadResponse.java
vo/WorkloadOverviewResponse.java
vo/ScoreBreakdown.java
vo/DispatchCandidateResponse.java
vo/DispatchExcludedResponse.java
vo/DispatchRecommendationResponse.java
vo/DispatchAssignResponse.java
vo/DispatchLogResponse.java
repository/AgentProfileRepository.java
repository/AgentSkillRepository.java
repository/TicketTypeSkillRuleRepository.java
repository/WorkloadRepository.java
repository/DispatchLogRepository.java
service/AgentService.java
service/SkillCatalogService.java
service/WorkloadService.java
service/DispatchService.java
strategy/ScoreStrategy.java
strategy/SkillMatchStrategy.java
strategy/WorkloadScoreStrategy.java
strategy/SlaUrgencyScoreStrategy.java
strategy/PerformanceScoreStrategy.java
controller/AgentController.java
controller/SkillController.java
controller/DispatchController.java
```

### 8.2 修改（后端）

- `auth/repository/UserRepository.java`：新增 `findAll()`、`findByRole("AGENT")`、`updateRole`。
- `auth/service/UserQueryService.java`：新增 `requireActiveAssigneeByName`（仅 ADMIN/AGENT），供工作流 assign/transfer 使用；`listAssignable()`。
- `auth/controller/UserController.java`：支持 `?assignable=true`。
- `ticket/service/TicketWorkflowService.java`：assign/transfer 改用 `requireActiveAssigneeByName`。
- `ticket/repository/TicketRepository.java`：新增按处理人与状态聚合所需的查询（或全部放到 `WorkloadRepository`，不改此文件）。
- `application.yml`：新增 `dispatch.*` 配置。
- `deploy/init/05-agent-dispatch.sql`（全新安装）与 `deploy/migrations/20261116-phase4-agent-dispatch.sql`（存量升级）。

### 8.3 新增（前端）

```
api/agents.ts
api/dispatch.ts
constants/dispatch.ts
components/LoadBar.vue
views/DispatchWorkbenchView.vue
views/AgentManagementView.vue
views/AgentWorkloadView.vue
views/DispatchHistoryView.vue
```

### 8.4 修改（前端）

- `constants/menu.ts`：智能调度四项改为真实路由。
- `router/index.ts`：注册四个视图。
- `constants/ticket.ts`：`ROLE_LABEL` 增加 AGENT。
- `components/AssignDrawer.vue`：候选改为 `/api/users?assignable=true`。

## 9. 数据库迁移方案

- 只新增表与种子数据，不改动 `users` / `tickets` 现有列，无破坏性变更。
- 使用 `CREATE TABLE IF NOT EXISTS` + `INSERT ... ON DUPLICATE KEY UPDATE`，可重复执行。
- 种子：8 个基础技能 + 工单类型—技能规则。
- **不伪造人员**：不插入任何假 AGENT 账号或历史绩效；验收人员由管理员通过接口创建。
- 存量升级脚本与全新安装脚本内容保持一致，便于校验。

## 10. 风险与对策

| 风险 | 级别 | 对策 |
|---|---|---|
| 收紧分配资格后，USER 账号不能再被分配 | 中 | 属预期行为；历史工单不受影响；前端候选改为可分配角色并给出提示 |
| 没有历史完成数据的 AGENT 绩效失真 | 中 | 样本门槛 + 中性分，明确标注「样本不足」，不编造数据 |
| 推荐与分配之间状态变化 | 中 | 确认分配重新校验并复用工作流条件更新，冲突返回 409 |
| 技能规则过度写死 | 低 | 规则落库，技能字典可扩展，自定义技能由管理员创建 |
| 本会话缺少 MySQL 密码，无法执行迁移 | 高（环境） | 代码与脚本先行；执行阶段向用户索取一次凭据，未获得则如实报告未验证项 |

## 11. 实施计划

1. 迁移脚本 + 种子数据。
2. 后端枚举/实体/DTO/仓储。
3. 人员与技能管理接口（含角色与权限边界）。
4. 负载统计接口。
5. 调度过滤、评分、推荐、确认分配、调度历史。
6. 单元测试与构建。
7. 前端 API/常量/组件/四个页面/路由菜单。
8. 执行迁移、重启、接口冒烟、浏览器验收、截图与文档。

## 12. 验证结果（2026-11-16）

- 迁移已在本地 `serviceflow` 库执行：新增 5 张表、8 个技能、10 条类型—技能规则；未修改 `users`/`tickets` 列。
- 后端单测 97 项全部通过，fat jar 打包成功；前端 `vue-tsc` + `vite build` 通过。
- `.tools/phase4-smoke.ps1` 冒烟 23 项通过、0 失败：推荐 → 确认分配 → 调度日志 → 历史全链路，以及 AGENT/未登录负例。
- 浏览器逐页验收通过，截图见 `docs/phase4/screenshots/`，详细报告见 `docs/phase4/acceptance.md`。
- 修复：`ticket_type_skill_rules.required`（TINYINT）被驱动映射为 Boolean，改用显式 `RowMapper` 读取。
- 环境说明：原会话缺少 MySQL 密码，已由用户提供后完成迁移与重启；新的 `JWT_SECRET` 会使旧登录态失效。

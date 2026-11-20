# Phase 3 任务清单

规格：[spec.md](spec.md) · 审计：[../phase3-audit.md](../phase3-audit.md)

> 每个任务完成即验证，不批量堆到最后。

## T1 数据库迁移与初始化脚本

- [x] 更新 `deploy/init/02-tickets.sql`：新增 `creator_id/assignee_id/sla_due_at/first_response_at/resolved_at/closed_at/cancelled_at/resolution/cancel_reason` 与索引，状态注释改为 7 状态。
- [x] 新增 `deploy/init/04-ticket-events.sql`：`ticket_events` 表。
- [x] 新增 `deploy/migrations/20261116-phase3-workflow.sql`：备份表、幂等加列、状态映射、SLA/时间戳回填、人员 id 回填、历史 `CREATE` 事件。
- [x] 对本地 MySQL 执行迁移。
- [x] 验证：状态分布符合 `OPEN→PENDING/ASSIGNED`、`IN_PROGRESS→PROCESSING`；`sla_due_at` 无空值。阶段二遗留临时工单 `SF20261116-VMH3` 已删除，当前 21 条真实工单（PENDING 6 / PROCESSING 5 / RESOLVED 6 / CLOSED 4）。

## T2 后端枚举、实体、DTO

- [x] 重写 `TicketStatus` 为 7 状态。
- [x] 新增 `TicketAction`、`TicketEventType`、`SlaState`。
- [x] 扩展 `Ticket` 实体 9 个字段。
- [x] 新增 `TicketEvent` 实体。
- [x] 新增 `TicketSlaResponse`、`TicketEventResponse`、`TicketAssignRequest`、`TicketResolveRequest`、`TicketCloseRequest`、`TicketCancelRequest`、`SlaPolicyResponse`、`UserOptionResponse`。
- [x] 改造 `TicketResponse`：SLA、`allowedActions`、`canEdit`、人员 id、时间戳。
- [x] 改造 `TicketUpdateRequest`：移除 `status/assignee`。
- [x] 验证：`mvn -q compile` 通过。

## T3 后端工作流引擎与 SLA 服务

- [x] 新增 `TicketWorkflowEngine`：流转目标、动作矩阵、权限判定。
- [x] 新增 `TicketSlaService`：策略、截止时间、状态评估。
- [x] 新增 `TicketWorkflowService`：7 个事务动作、条件更新、事件写入、409。
- [x] 新增 `TicketEventRepository`、扩展 `TicketRepository`（insert 新列、`updateContent`、`updateWorkflowState`、`updateSlaDueAt`）。
- [x] 改造 `TicketService`：创建写 SLA/事件；查询组装；更新只改内容；删除级联事件。
- [x] 新增 `ConflictException` 并在 `GlobalExceptionHandler` 注册 409。
- [x] 验证：编译通过。

## T4 后端接口

- [x] `TicketController` 新增 `assign/transfer/accept/start/resolve/close/cancel` 与 `GET /events`。
- [x] 新增 `SlaController` 的 `GET /api/sla/policies`。
- [x] 扩展 `UserRepository.findAllActive`，新增 `UserQueryService`、`UserController` 的 `GET /api/users`。
- [x] 验证：`.\mvnw.cmd -q test`、`package` 通过。

## T5 后端单元测试

- [x] `TicketWorkflowEngineTest`：每个状态的合法动作、越权、终态。
- [x] `TicketSlaServiceTest`：4/8/24/72、WARNING、OVERDUE、COMPLETED、优先级重算。
- [x] 验证：测试全绿。

## T6 启动与接口冒烟

- [x] 停止旧后端 8080，使用现有环境变量启动新包。
- [x] admin 登录，`GET /api/tickets` 检查 `sla/allowedActions`。
- [x] 创建临时工单 → 分配 bob → bob 接单/开始 → bob 解决 → admin 关闭；检查 `GET /events`。
- [x] 反向用例：非法流转 400、越权 403、过期状态 409。
- [x] 删除本次冒烟临时工单，21 条历史数据不受影响。

## T7 前端 API 与常量

- [x] 重写 `api/tickets.ts` 类型与 7 个动作接口，删除全量 PUT 改状态。
- [x] 新增 `api/users.ts`、`api/sla.ts`。
- [x] 重写 `constants/ticket.ts`：7 状态、SLA 读取后端、动作元数据、7 列看板。
- [x] 更新 `StatusTag.vue`、`SlaBadge.vue`。
- [x] 验证：`npm run build` 通过。

## T8 前端三页接入

- [x] `TicketsView.vue`：KPI、快捷筛选、状态筛选、行操作权限、SLA。
- [x] `TicketDetailView.vue`：动作按钮、事件时间线、SLA 卡、取消态。
- [x] `TicketBoardView.vue`：7 列、拖拽调用后端、非法拖拽提示。
- [x] `TicketFormModal.vue`：编辑不再改状态/处理人；创建保留可选处理人。
- [x] `AssignDrawer.vue`：真实用户列表，分配/转交接口。
- [x] 验证：`npm run build` 通过。

## T9 其他页面与路由

- [x] `MyTicketsView.vue`、`DashboardView.vue`、`useTicketStore.ts` 适配新状态与 SLA。
- [x] 新增 `SlaManagementView.vue`（策略 + 监控）。
- [x] 新增 `WorkflowOverviewView.vue`（状态规则 + 实时计数）。
- [x] `router/index.ts`、`constants/menu.ts` 启用 `/workflow`、`/workflow/sla`。
- [x] 验证：构建通过。

## T10 浏览器验收与回归

- [x] 登录 admin 验收并截图 `/tickets`、`/tickets/{id}`、`/tickets/board`、`/workflow`、`/workflow/sla`（截图见 `screenshots/`）。
- [x] 检查列表筛选、分页、详情跳转、动作按钮、看板拖拽：非法拖拽“处理中→待分配”提示可读且不改变数据。
- [x] 普通用户 bob 权限验证：删除 403、对 PENDING 执行 start 403。
- [x] 控制台无业务红色错误；1440 宽度页面完整。
- [x] 回归登录、驾驶舱、我的工单、工单列表、看板、详情。
- [x] 删除阶段二遗留临时工单，工单总数恢复 21 条。

## T11 交付

- [x] `docs/phase3-audit.md` 与实施结果一致，无需返工。
- [x] 输出修改文件清单、接口清单、迁移说明、截图路径、已/未完成项。

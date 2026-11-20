# Phase 3 规格：工单工作流引擎 + SLA 管理

状态：待确认
依赖审计：[phase3-audit.md](../phase3-audit.md)

## 1. 目标与非目标

目标：

1. 在现有 Spring Boot + JdbcTemplate + JWT 架构内落地统一 7 状态工作流，所有状态变更由服务端校验。
2. 落地后端 SLA 服务：策略、截止时间持久化、剩余时间、超时/预警状态。
3. 新增工单事件表，详情页时间线/操作记录改为真实数据。
4. 前端三页（列表、详情、看板）接入新接口；看板启用拖拽，但只能触发后端允许的流转。
5. 新增 SLA 管理页与工作流总览页，均使用真实数据。
6. 保持 Phase 1/2 的布局、视觉、登录、CRUD、驾驶舱与 21 条历史数据可用。

非目标：

- 不做评论、附件、知识库、AI 分类（继续标注待接入）。
- 不做可视化流程编排器；工作流规则由代码状态机定义。
- 不做 SLA 策略在线编辑；策略只读展示，修改需改代码并重启。
- 不引入 Flyway/MyBatis/Redis/消息队列等新依赖。

## 2. 统一状态模型

```java
TicketStatus { PENDING, ASSIGNED, ACCEPTED, PROCESSING, RESOLVED, CLOSED, CANCELLED }
```

| 编码 | 名称 | 含义 |
|---|---|---|
| PENDING | 待分配 | 新建或解除分配后等待处理人 |
| ASSIGNED | 已分配 | 已有处理人，等待接单 |
| ACCEPTED | 已接单 | 处理人已接单，尚未开始 |
| PROCESSING | 处理中 | 正在处理 |
| RESOLVED | 已解决 | 已给出解决方案，等待关闭 |
| CLOSED | 已关闭 | 正常结束 |
| CANCELLED | 已取消 | 非正常结束，停止 SLA 计时 |

`CLOSED` 与 `CANCELLED` 为终态。

## 3. 状态机与权限

动作枚举 `TicketAction { ASSIGN, TRANSFER, ACCEPT, START, RESOLVE, CLOSE, CANCEL }`。

| 当前状态 | 动作 | 目标状态 | 允许角色 |
|---|---|---|---|
| PENDING | ASSIGN | ASSIGNED | ADMIN 或创建人 |
| PENDING | CANCEL | CANCELLED | ADMIN 或创建人 |
| ASSIGNED | ACCEPT | ACCEPTED | 当前处理人 或 ADMIN |
| ASSIGNED | TRANSFER | ASSIGNED | 当前处理人 或 ADMIN |
| ASSIGNED | CANCEL | CANCELLED | ADMIN 或创建人 |
| ACCEPTED | START | PROCESSING | 当前处理人 或 ADMIN |
| ACCEPTED | TRANSFER | ASSIGNED | 当前处理人 或 ADMIN |
| ACCEPTED | CANCEL | CANCELLED | ADMIN 或创建人 |
| PROCESSING | RESOLVE | RESOLVED | 当前处理人 或 ADMIN |
| PROCESSING | TRANSFER | ASSIGNED | 当前处理人 或 ADMIN |
| PROCESSING | CANCEL | CANCELLED | ADMIN 或创建人 |
| RESOLVED | CLOSE | CLOSED | ADMIN 或创建人 |
| CLOSED / CANCELLED | 无 | - | - |

判定细节：

- 处理人优先比较 `assignee_id == 当前用户 id`；历史数据 `assignee_id` 为空时，回退比较 `assignee` 与当前用户显示名。
- 创建人优先比较 `creator_id`；历史数据为空时回退比较 `creator` 与当前用户显示名。
- `ASSIGN` / `TRANSFER` 的处理人必须是 `users.status = 1` 的真实用户（按 `name` 匹配，`name` 不唯一时拒绝并提示）。
- `TRANSFER` 后状态回到 `ASSIGNED`，新处理人必须重新接单；`first_response_at` 保留首次接单时间。
- `ACCEPT` 写入 `first_response_at`（仅首次），并进入 `ACCEPTED`。
- 普通用户不能执行管理员专属动作；越权返回 403。

并发控制：

- 所有流转使用 `UPDATE tickets ... WHERE id = ? AND status = ?`。
- 影响行数为 0 且工单存在 → 409「工单状态已变更，请刷新后重试」。

## 4. SLA 模型

策略固定（与 Phase 2 前端临时口径一致）：

| 优先级 | 响应时限 | 预警阈值 |
|---|---|---|
| URGENT | 4 小时 | 剩余 ≤ 120 分钟 |
| HIGH | 8 小时 | 剩余 ≤ 120 分钟 |
| MEDIUM | 24 小时 | 剩余 ≤ 120 分钟 |
| LOW | 72 小时 | 剩余 ≤ 120 分钟 |

字段：

- `sla_due_at = created_at + 策略小时数`，创建时写入；编辑优先级时按新策略重算。
- `first_response_at`：首次 `ACCEPT` 时间。
- `resolved_at` / `closed_at` / `cancelled_at`：对应动作发生时间。
- `SlaState { ON_TRACK, WARNING, OVERDUE, COMPLETED }`
  - `RESOLVED/CLOSED/CANCELLED` → `COMPLETED`，不再判定超时。
  - 未结束且 `now > sla_due_at` → `OVERDUE`。
  - 未结束且剩余 ≤ 120 分钟 → `WARNING`。
  - 其余 → `ON_TRACK`。
- `remainingMinutes`：向上取整；超时为负。
- `elapsedPercent`：0-100，超时封顶 100。

## 5. 数据库变更

### 5.1 `tickets` 新列

```sql
creator_id       BIGINT UNSIGNED NULL
assignee_id      BIGINT UNSIGNED NULL
sla_due_at       DATETIME NULL
first_response_at DATETIME NULL
resolved_at      DATETIME NULL
closed_at        DATETIME NULL
cancelled_at     DATETIME NULL
resolution       TEXT NULL
cancel_reason    VARCHAR(500) NULL
KEY idx_tickets_sla_due_at (sla_due_at)
KEY idx_tickets_assignee_id (assignee_id)
```

`status` 注释更新为 7 状态；`VARCHAR(16)` 长度足够，不改类型。

### 5.2 `ticket_events`

```sql
CREATE TABLE IF NOT EXISTS ticket_events (
  id BIGINT UNSIGNED PK AUTO_INCREMENT,
  ticket_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(24) NOT NULL,          -- CREATE/UPDATE/ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NULL,
  operator_id BIGINT UNSIGNED NULL,
  operator_name VARCHAR(64) NOT NULL,
  note VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ticket_events_ticket (ticket_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

不建外键，保持与现有表一致；删除工单时同步删除事件（事务内）。

### 5.3 迁移步骤（`deploy/migrations/20261116-phase3-workflow.sql`）

1. `CREATE TABLE IF NOT EXISTS tickets_phase2_backup AS SELECT * FROM tickets;`（仅首次）
2. 用存储过程按 `information_schema.columns` 判断并 `ADD COLUMN`，保证可重复执行。
3. 状态映射：
   ```sql
   UPDATE tickets SET status='ASSIGNED'
    WHERE status='OPEN' AND assignee IS NOT NULL AND TRIM(assignee) <> '';
   UPDATE tickets SET status='PENDING' WHERE status='OPEN';
   UPDATE tickets SET status='PROCESSING' WHERE status='IN_PROGRESS';
   ```
4. 回填 `sla_due_at`、`resolved_at`、`closed_at`。
5. 按 `users.name` 回填 `creator_id`、`assignee_id`。
6. 创建 `ticket_events`，为每个历史工单补 `CREATE` 事件（`NOT EXISTS` 防重）。
7. 输出迁移前后状态计数，便于核对 21 条数据。

同时更新 `deploy/init/02-tickets.sql`，新增 `deploy/init/04-ticket-events.sql`，保证全新安装直接得到新结构。

## 6. API 契约

所有响应沿用 `ApiResponse<T>`；错误 `400/401/403/404/409/500`。

### 6.1 工单响应 `TicketResponse`

```json
{
  "id": 1,
  "ticketNo": "SF20261116-AB12",
  "title": "打印机无法连接",
  "description": "...",
  "type": "INCIDENT",
  "priority": "HIGH",
  "status": "ASSIGNED",
  "creator": "系统管理员",
  "creatorId": 1,
  "assignee": "小明",
  "assigneeId": 2,
  "createdAt": "2026-11-16T09:00:00",
  "updatedAt": "2026-11-16T09:10:00",
  "firstResponseAt": null,
  "resolvedAt": null,
  "closedAt": null,
  "cancelledAt": null,
  "sla": {
    "policyLabel": "高优先级 8 小时响应",
    "hours": 8,
    "dueAt": "2026-11-16T17:00:00",
    "state": "ON_TRACK",
    "remainingMinutes": 360,
    "elapsedPercent": 25,
    "completedAt": null
  },
  "allowedActions": ["ACCEPT", "TRANSFER", "CANCEL"],
  "canEdit": true
}
```

`allowedActions` 随当前登录用户计算；前端只负责渲染，服务端仍二次校验。

### 6.2 工作流接口

| 方法 | 路径 | 请求体 | 说明 |
|---|---|---|---|
| POST | `/api/tickets/{id}/assign` | `{"assignee":"小明"}` | PENDING→ASSIGNED |
| POST | `/api/tickets/{id}/transfer` | `{"assignee":"小明"}` | 任意活动状态→ASSIGNED |
| POST | `/api/tickets/{id}/accept` | 无 | ASSIGNED→ACCEPTED |
| POST | `/api/tickets/{id}/start` | 无 | ACCEPTED→PROCESSING |
| POST | `/api/tickets/{id}/resolve` | `{"resolution":"已重启服务"}` | PROCESSING→RESOLVED |
| POST | `/api/tickets/{id}/close` | `{"note":"确认解决"}` | RESOLVED→CLOSED |
| POST | `/api/tickets/{id}/cancel` | `{"reason":"重复工单"}` | 活动状态→CANCELLED |

均返回更新后的 `TicketResponse`。

### 6.3 事件与用户

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tickets/{id}/events` | 按时间正序返回 `TicketEventResponse[]` |
| GET | `/api/users` | 返回启用用户 `{id, username, name, role}`，供分配下拉 |

### 6.4 SLA

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/sla/policies` | 返回 4 条策略与 120 分钟预警阈值 |

`SlaPolicyResponse { priority, hours, warningMinutes, label }`。

### 6.5 既有接口调整

- `POST /api/tickets`：仍可传可选 `assignee`；有处理人时初始状态 `ASSIGNED`，否则 `PENDING`。
- `PUT /api/tickets/{id}`：只允许改 `title/description/type/priority`；请求中出现 `status` 或 `assignee` 直接 400，提示使用工作流接口。优先级变化时重算 `sla_due_at` 并写入 `UPDATE` 事件。
- `DELETE /api/tickets/{id}`：ADMIN，事务内先删事件再删工单。

## 7. 后端文件级实施

| 文件 | 变更 |
|---|---|
| `ticket/enums/TicketStatus.java` | 7 状态；`terminal()`、`active()` |
| `ticket/enums/TicketAction.java` | ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL |
| `ticket/enums/TicketEventType.java` | CREATE/UPDATE + 上述动作 |
| `ticket/enums/SlaState.java` | ON_TRACK/WARNING/OVERDUE/COMPLETED |
| `ticket/entity/Ticket.java` | 新增 9 个字段与 getter/setter |
| `ticket/entity/TicketEvent.java` | 事件实体 |
| `ticket/dto/TicketSlaResponse.java` | SLA 响应 record |
| `ticket/dto/TicketEventResponse.java` | 事件响应 record |
| `ticket/dto/TicketAssignRequest.java` | `@NotBlank assignee` |
| `ticket/dto/TicketResolveRequest.java` | `resolution` ≤ 1000 |
| `ticket/dto/TicketCloseRequest.java` | `note` ≤ 500 |
| `ticket/dto/TicketCancelRequest.java` | `reason` ≤ 500 |
| `ticket/dto/SlaPolicyResponse.java` | 策略响应 |
| `ticket/dto/TicketResponse.java` | 增加 SLA/动作/canEdit/人员 id/时间戳；`from(Ticket, sla, actions, canEdit)` |
| `ticket/dto/TicketUpdateRequest.java` | 删除 `status`、`assignee` 字段 |
| `ticket/repository/TicketRepository.java` | insert 新列；`updateContent`；`updateWorkflowState(t, expectedStatus)`；`updateSlaDueAt`；删除按 id |
| `ticket/repository/TicketEventRepository.java` | `insert`、`findByTicketId`、`deleteByTicketId` |
| `ticket/workflow/TicketWorkflowEngine.java` | 纯函数：`targetStatus(from, action)`、`allowedActions(ticket,user)`、`assertAllowed(...)` |
| `ticket/service/TicketSlaService.java` | `policy(priority)`、`dueAt(createdAt, priority)`、`evaluate(ticket, now?)` |
| `ticket/service/TicketWorkflowService.java` | 7 个事务方法；条件更新；写事件；409 |
| `ticket/service/TicketService.java` | 创建写 SLA/事件；查询组装 SLA/动作；更新只改内容；删除级联事件 |
| `ticket/controller/TicketController.java` | 新增 7 个 POST 与 `GET /events` |
| `ticket/controller/SlaController.java` | `GET /api/sla/policies` |
| `auth/repository/UserRepository.java` | `findAllActive()` |
| `auth/service/UserQueryService.java` | 过滤启用用户并转 DTO |
| `auth/controller/UserController.java` | `GET /api/users` |
| `auth/dto/UserOptionResponse.java` | id/username/name/role |
| `common/exception/ConflictException.java` | 409 |
| `common/exception/GlobalExceptionHandler.java` | 注册 409 |
| `test/.../TicketWorkflowEngineTest.java` | 合法/非法流转、权限矩阵 |
| `test/.../TicketSlaServiceTest.java` | 4/8/24/72、预警、超时、完成 |

## 8. 前端文件级实施

### 8.1 API 与常量

- `api/tickets.ts`：重写 `Ticket` 类型；新增 `assignTicket/transferTicket/acceptTicket/startTicket/resolveTicket/closeTicket/cancelTicket/getTicketEvents`；删除 `changeTicketStatus` 的全量 PUT 实现。
- `api/users.ts`：`listUsers()`。
- `api/sla.ts`：`listSlaPolicies()`。
- `constants/ticket.ts`：
  - 7 状态标签/颜色/说明/权重；`STATUS_FLOW` 为 `PENDING→ASSIGNED→ACCEPTED→PROCESSING→RESOLVED→CLOSED`。
  - `getSla(ticket)` 改为读取 `ticket.sla`，映射为现有 `SlaState` 小写口径（`ok/warn/overdue/done`），不再本地推算截止时间。
  - `ACTIONS` 元数据表：`ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL` 的文案、图标、提示。
  - `BOARD_COLUMNS` 改为 7 列，直接对应 7 个状态。
- `components/StatusTag.vue`：补齐 7 个状态样式。
- `components/SlaBadge.vue`：继续使用 `getSla`，提示文案改为“后端 SLA 策略”。

### 8.2 三页改造

- `TicketsView.vue`：
  - KPI：总量、待分配(`PENDING`)、处理中(`ACCEPTED+PROCESSING`)、SLA 超时。
  - 快捷筛选：待分配、处理中、SLA 超时；状态筛选 7 项。
  - 行操作：详情、编辑（仅 `canEdit`）、分配（仅 `allowedActions` 含 `ASSIGN` 或 `TRANSFER`）、删除（ADMIN）。
  - SLA 徽标读取后端；删除“临时口径/待接入”文案。
- `TicketDetailView.vue`：
  - 流程条 6 步主路径，`CANCELLED` 单独红色提示。
  - 动作按钮由 `ticket.allowedActions` 驱动；`RESOLVE/CLOSE/CANCEL` 使用输入弹层/`prompt` 填写说明后调用对应接口。
  - 时间线、操作记录改为 `GET /events` 真实数据；`CREATE/UPDATE/ASSIGN/...` 中文映射。
  - SLA 卡显示策略、截止时间、首次响应、剩余/超时、进度条；移除“临时口径”。
  - 评论与附件继续保留“待接入”说明。
- `TicketBoardView.vue`：
  - 7 列；列计数与卡片来自真实状态。
  - 启用 HTML5 拖拽：仅当目标状态对应动作在 `allowedActions` 中才调用接口；`ASSIGNED` 目标打开分配抽屉；`RESOLVED/CLOSED/CANCELLED` 需填写说明；非法拖拽给出可读提示。
  - 移除“拖拽待接入”提示，改为“拖拽由后端状态机校验”。

### 8.3 其他页面

- `MyTicketsView.vue`：待处理=`PENDING`，进行中=`ASSIGNED+ACCEPTED+PROCESSING`。
- `DashboardView.vue`：状态分布 7 项；趋势的“处理中”= `ACCEPTED+PROCESSING`；我的待办按新状态。
- `useTicketStore.ts`：新增 `overdueCount`；更新 `unassignedCount/inProgressCount/doneCount/buildTrend/buildMyTodos`。
- `SlaManagementView.vue`：策略表（后端接口）+ 当前工单 SLA 监控（正常/预警/超时/完成数量与超时 Top 列表），数据来自 store。
- `WorkflowOverviewView.vue`：7 状态节点、合法流转规则、当前各状态数量；规则与后端枚举保持一致，不伪造业务数据。
- `router/index.ts`：`/workflow`、`/workflow/sla` 指向真实页面。
- `constants/menu.ts`：移除两项 `soon`。

## 9. 兼容与回滚

- 迁移前自动备份 `tickets_phase2_backup`。
- 回滚：停后端 → 用备份表恢复 `tickets` 的 `status/assignee/updated_at` → 回滚前后端代码。新增列可保留，不影响旧代码。
- 旧前端缓存（localStorage）只存 token/user，不存工单，无迁移问题。
- 历史自由文本处理人保留展示；分配/转交只接受真实启用用户。

## 10. 验证计划

1. **迁移验证**：执行迁移脚本；`SELECT status, COUNT(*) FROM tickets GROUP BY status` 总数仍为 21，分布符合映射。
2. **后端构建**：`backend-java> .\mvnw.cmd -q test` 与 `.\mvnw.cmd -q -DskipTests package` 通过。
3. **接口冒烟**（真实 MySQL）：
   - admin 登录 → `GET /api/tickets` 返回 `sla`、`allowedActions`。
   - 创建临时工单 → 分配 bob → bob 登录接单/开始/解决 → admin 关闭 → `GET /events` 顺序正确。
   - 非法流转（如 PENDING 直接 start）→ 400；bob 关闭非自己工单 → 403；并发旧状态 → 409。
   - 创建临时工单后删除，清理验证数据。
4. **前端构建**：`frontend> D:\Node\npm.cmd run build` 通过（含 vue-tsc）。
5. **浏览器验收**：
   - `/tickets` 列表 21 条、筛选、分页、SLA 徽标。
   - `/tickets/{id}` 详情动作、事件时间线、SLA 卡。
   - `/tickets/board` 7 列与拖拽合法/非法提示。
   - `/workflow/sla`、`/workflow` 真实数据。
   - 控制台 0 错误；1366/1440/1920 宽度不破版。
6. **回归**：登录、驾驶舱、我的工单、ADMIN 删除、普通用户权限。

## 11. 需要确认的决策

1. 7 状态与流转矩阵按第 3 节执行。
2. 旧 `PUT /api/tickets/{id}` 不再允许改 `status/assignee`。
3. 分配/转交只接受 `users` 表中启用的真实用户。
4. SLA 仍为 4/8/24/72 小时，预警阈值 120 分钟，策略只读。
5. 迁移会先备份再改 21 条历史工单状态。

# Phase 3 审计报告：工作流引擎 + SLA 管理

审计时间：2026-11-16
审计范围：`backend-java`、`frontend`、`deploy` 现有源码与建表脚本（实际阅读，非按文件名推断）。

## 1. 当前真实架构

```
backend-java
└─ src/main/java/com/serviceflow/ai
   ├─ auth
   │  ├─ controller/AuthController        POST /api/auth/login
   │  ├─ context/UserContext              ThreadLocal 登录上下文
   │  ├─ context/LoginUser                id / username / name / role
   │  ├─ interceptor/AuthInterceptor      Bearer JWT + 每请求查库
   │  ├─ jwt/JwtService                   签发与解析
   │  ├─ repository/UserRepository        JdbcTemplate
   │  ├─ service/AuthService              登录 + 初始 admin
   │  └─ enums/UserRole                   ADMIN / AGENT / USER
   ├─ ticket
   │  ├─ controller/TicketController      5 个 CRUD 接口
   │  ├─ service/TicketService            编号生成、枚举校验、删除权限
   │  ├─ repository/TicketRepository      JdbcTemplate + BeanPropertyRowMapper
   │  ├─ entity/Ticket
   │  ├─ dto/TicketCreateRequest / TicketUpdateRequest / TicketResponse
   │  └─ enums/TicketStatus / Priority / TicketType
   ├─ common
   │  ├─ config/WebMvcConfig              拦截 /api/**，放行 login 与 health
   │  ├─ dto/ApiResponse / PageResponse
   │  └─ exception/GlobalExceptionHandler 400/401/403/404/500
   └─ health                                健康检查
```

前端：

```
frontend/src
├─ api/request.ts         fetch 封装、Bearer、401 跳登录
├─ api/tickets.ts         工单类型与 CRUD（状态变更走全量 PUT）
├─ composables/useTicketStore.ts  全量拉取 + 前端聚合统计
├─ constants/ticket.ts    状态标签、SLA 临时口径、流转入口、看板列
├─ components/            SlaBadge / StatusTag / AssignDrawer / DataTable / TicketFormModal
└─ views/                 TicketsView / TicketDetailView / TicketBoardView / MyTicketsView / DashboardView
```

## 2. 当前工单状态定义

后端 `TicketStatus`：

| 枚举 | 前端文案 | 数据库 |
|---|---|---|
| OPEN | 待处理 | 21 条历史数据正在使用 |
| IN_PROGRESS | 处理中 | 正在使用 |
| RESOLVED | 已解决 | 正在使用 |
| CLOSED | 已关闭 | 正在使用 |

数据库 `tickets.status` 为 `VARCHAR(16)`，默认 `OPEN`，无 `CHECK` 约束；无自动建表/迁移框架，只有 `deploy/init` 下的初始化脚本。
`TicketStatus` 与 `Priority` 均为 Java 枚举，但 `TicketService.update` 直接接受任意枚举值，**没有前后状态合法性校验**。

## 3. 当前接口清单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录 |
| GET | `/api/health/**` | 公开 | 健康检查 |
| POST | `/api/tickets` | 登录 | 创建工单，状态固定 `OPEN`，creator 取登录显示名 |
| GET | `/api/tickets` | 登录 | 分页 + status/priority 筛选，page 从 1 开始，size ≤ 100 |
| GET | `/api/tickets/{id}` | 登录 | 详情 |
| PUT | `/api/tickets/{id}` | 登录 | 全量更新，可任意改 `status` / `assignee` |
| DELETE | `/api/tickets/{id}` | ADMIN | 删除 |

**不存在**：分配、接单、开始处理、解决、关闭、取消、事件/操作日志、SLA、用户列表、评论、附件、统计接口。

## 4. 当前数据库表结构

`deploy/init/01-init.sql`：创建 `serviceflow` 库。

`deploy/init/02-tickets.sql`：

```
tickets(
  id BIGINT UNSIGNED PK AUTO_INCREMENT,
  ticket_no VARCHAR(32) UNIQUE,
  title VARCHAR(200),
  description TEXT NULL,
  type VARCHAR(32),
  priority VARCHAR(16) DEFAULT 'MEDIUM',
  status VARCHAR(16) DEFAULT 'OPEN',
  creator VARCHAR(64),
  assignee VARCHAR(64) NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_tickets_status / idx_tickets_priority / idx_tickets_created_at
)
```

`deploy/init/03-users.sql`：`users(id, username, password, name, role, status, created_at, updated_at)`，`role` 默认 `USER`，`status=1` 正常。

现有数据：21 条真实工单；`users` 至少有 admin（系统管理员）与 bob（小明）。**没有** `ticket_events`、评论、附件、SLA 策略表。

## 5. 前端数据接口与 SLA 现状

- 列表/看板/驾驶舱/我的工单全部通过 `listAllTickets()` 分页拉全量后在前端聚合，没有统计接口。
- `constants/ticket.ts` 的 `getSla()` 用前端常量 `URGENT 4h / HIGH 8h / MEDIUM 24h / LOW 72h`，从 `createdAt` 推算截止时间；`RESOLVED/CLOSED` 视为计时结束。
- 详情页时间线只展示 `createdAt`、`updatedAt`，并明确标注“逐次流转记录待接入”。
- 看板 6 列，`待分配/已分配` 都映射到后端 `OPEN` 并用 `assignee` 区分，`已接单` 列标注“后端暂无状态”；拖拽未启用。
- `AssignDrawer` 处理人为自由文本，候选来自历史工单，没有真实用户列表。
- `TicketFormModal` 编辑时可直接改 `status` 和 `assignee`，最终走全量 `PUT`。

## 6. 已有功能与缺失功能

已有且必须保留：

- JWT 登录、401 跳转、ADMIN 删除权限。
- 工单创建、编辑内容、详情、分页筛选、驾驶舱统计、列表/看板/我的工单三页。
- 真实数据库数据，无 mock。
- 前端视觉体系与构建流程（Vue 3 + TS strict + Vite）。

缺失（Phase 3 范围）：

1. 统一 7 状态枚举与旧数据迁移。
2. 服务端状态机、权限归属校验、并发保护。
3. 分配/接单/开始/解决/关闭/取消/转交接口。
4. 状态流转事件表与查询接口，支撑详情时间线与操作记录。
5. 后端 SLA 策略、截止时间持久化、剩余/超时计算。
6. 真实用户列表，支撑分配与转交。
7. 前端改用后端 SLA 与后端允许动作，启用看板拖拽。
8. SLA 管理页（策略与当前监控）。

## 7. 需要修改的文件

### 后端

新增：

- `ticket/enums/TicketStatus.java`（重写为 7 状态）
- `ticket/enums/TicketAction.java`
- `ticket/enums/TicketEventType.java`
- `ticket/enums/SlaState.java`
- `ticket/entity/TicketEvent.java`
- `ticket/dto/TicketSlaResponse.java`
- `ticket/dto/TicketEventResponse.java`
- `ticket/dto/TicketAssignRequest.java`
- `ticket/dto/TicketResolveRequest.java`
- `ticket/dto/TicketCloseRequest.java`
- `ticket/dto/TicketCancelRequest.java`
- `ticket/dto/SlaPolicyResponse.java`
- `ticket/repository/TicketEventRepository.java`
- `ticket/workflow/TicketWorkflowEngine.java`
- `ticket/service/TicketWorkflowService.java`
- `ticket/service/TicketSlaService.java`
- `ticket/controller/SlaController.java`
- `auth/controller/UserController.java`
- `auth/dto/UserOptionResponse.java`
- `auth/service/UserQueryService.java`
- `common/exception/ConflictException.java`

修改：

- `ticket/entity/Ticket.java`（新增 SLA/时间戳/人员 id 字段）
- `ticket/dto/TicketResponse.java`（SLA、允许动作、canEdit）
- `ticket/dto/TicketUpdateRequest.java`（禁止直接改 status/assignee）
- `ticket/repository/TicketRepository.java`
- `ticket/service/TicketService.java`
- `ticket/controller/TicketController.java`
- `auth/repository/UserRepository.java`（`findAllActive`）
- `common/exception/GlobalExceptionHandler.java`（409）
- `docs/phase3/spec.md`、`docs/phase3/tasks.md`（本规格）

### 前端

- `api/tickets.ts`（类型与 7 个工作流接口）
- `api/users.ts`（新增）
- `api/sla.ts`（新增）
- `constants/ticket.ts`（状态、SLA 适配、动作元数据、7 列看板）
- `components/StatusTag.vue`
- `components/SlaBadge.vue`
- `components/AssignDrawer.vue`
- `components/TicketFormModal.vue`
- `views/TicketsView.vue`
- `views/TicketDetailView.vue`
- `views/TicketBoardView.vue`
- `views/MyTicketsView.vue`
- `views/DashboardView.vue`
- `views/SlaManagementView.vue`（新增）
- `views/WorkflowOverviewView.vue`（新增）
- `router/index.ts`
- `constants/menu.ts`
- `composables/useTicketStore.ts`

### 数据库

- 修改 `deploy/init/02-tickets.sql`（新列 + 状态注释，供全新安装）
- 新增 `deploy/init/04-ticket-events.sql`
- 新增 `deploy/migrations/20261116-phase3-workflow.sql`（本机已有库迁移，含备份、状态映射、SLA 回填）

## 8. 是否需要数据库迁移

**需要**，且必须先迁移再启动新后端，否则新代码读到旧 `OPEN/IN_PROGRESS` 会无法映射。

必须处理：

- `OPEN` → `PENDING`（无处理人）或 `ASSIGNED`（有处理人）
- `IN_PROGRESS` → `PROCESSING`
- `RESOLVED` / `CLOSED` 保持不变
- 回填 `sla_due_at = created_at + 优先级时限`
- 回填 `resolved_at/closed_at`（用现有 `updated_at` 近似）
- 按 `users.name` 回填 `creator_id/assignee_id`（匹配不到留空，兼容历史自由文本）
- 创建 `ticket_events` 并为历史工单补一条 `CREATE` 事件
- 迁移前创建 `tickets_phase2_backup` 快照

## 9. 风险

1. **状态枚举切换是最大风险**：前后端必须同批发布；旧前端遇到新状态会显示原始英文。
2. **旧 `PUT` 状态入口被关闭**：任何仍直接提交 `status` 的调用会得到 400，需改用工作流接口。
3. **处理人从自由文本收敛为真实用户**：历史 `assignee` 若匹配不到 `users.name`，仍可展示，但不能再作为分配候选；接单权限按 `assignee_id` 优先、显示名兜底。
4. **并发流转**：两个请求同时改状态时，必须用 `WHERE id=? AND status=?` 条件更新，避免覆盖；冲突返回 409。
5. **SLA 口径变化**：仍沿用 4/8/24/72 小时，不改变业务预期；但截止时间改为后端持久化，优先级变更会重算。
6. **不可逆数据操作**：迁移脚本先备份；回滚时用备份表恢复 `status` 与新增列。

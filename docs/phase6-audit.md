# 第六阶段现状审计：数据分析与运营管理中心

> 生成依据：实际读取的建表 SQL（`deploy/init/01-init.sql`、`02-tickets.sql`、`03-users.sql`、`04-ticket-events.sql`、`05-agent-dispatch.sql`，`deploy/migrations/20261116-phase3-workflow.sql`、`20261116-phase4-agent-dispatch.sql`、`20261116-phase5-ai-knowledge.sql`）与 Java 源码、前端源码。
> 本文只记录事实，不提出新增业务字段；所有字段名、枚举值均来自上述 SQL 与 Java 枚举。

---

## 1. 技术栈与运行约定

| 项 | 现状（来源） |
| --- | --- |
| 前端 | Vue 3.5 + Vite 7 + TypeScript 5.9；`createWebHashHistory`（hash 路由）；ECharts 6.1（`frontend/package.json`） |
| 图表注册 | `BaseChart.vue` 按需 `use([LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])`，未注册 DataZoom/Graphic 等扩展组件 |
| 后端 | Spring Boot 3.5.16、Java 17、Spring JDBC `JdbcTemplate`（`backend-java/pom.xml`）；包结构 `com.serviceflow.ai.<domain>.<layer>` |
| 数据库 | MySQL 8.x，库名 `serviceflow`，`utf8mb4 / utf8mb4_unicode_ci`（`01-init.sql`） |
| 响应体 | `common/dto/ApiResponse<T>{int code, String message, T data}`；成功 `ok()` → `{200,"success",data}`；分页 `PageResponse<T>` |
| 认证 | `AuthInterceptor` 保护 `/api/**`；`UserContext.getRequired()` 返回 `LoginUser(Long id, String username, String name, UserRole role)`；`UserContext.remove()` 在 `afterCompletion` 清理 |
| 角色 | `UserRole{ADMIN, AGENT, USER}`，与 `users.role` 字符串一致 |
| 异常 | `GlobalExceptionHandler`：`MethodArgumentNotValidException`/`HandlerMethodValidationException`/`IllegalArgumentException`→400，`UnauthorizedException`→401，`ForbiddenException`→403，`ConflictException`→409，`NotFoundException`→404，其余→500 |
| 权限写法 | 现有模块均使用独立 `*Security`（如 `DispatchSecurity.requireAdminOrAgent()`），统计模块沿用该模式 |
| 时区 | 后端全部使用 `LocalDate`/`LocalDateTime`/`LocalDateTime.now()`，按 JVM 默认时区解释日期边界 |

---

## 2. 表结构与索引（逐表，字段以建表 SQL 为准）

### 2.1 `tickets`（工单主表，`02-tickets.sql` + phase3 迁移补列）

| 字段 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 主键 |
| `ticket_no` | VARCHAR(32) | 否 | — | 工单编号，如 `SF20261114-AB12` |
| `title` | VARCHAR(200) | 否 | — | 标题 |
| `description` | TEXT | 是 | NULL | 描述 |
| `type` | VARCHAR(32) | 否 | — | 类型枚举 |
| `priority` | VARCHAR(16) | 否 | `'MEDIUM'` | 优先级枚举 |
| `status` | VARCHAR(16) | 否 | `'PENDING'` | 状态枚举 |
| `creator` | VARCHAR(64) | 否 | — | 创建人显示名 |
| `creator_id` | BIGINT UNSIGNED | 是 | NULL | 创建人 `users.id`，历史数据可能为空 |
| `assignee` | VARCHAR(64) | 是 | NULL | 处理人显示名 |
| `assignee_id` | BIGINT UNSIGNED | 是 | NULL | 处理人 `users.id`，历史自由文本匹配不到时为 NULL |
| `sla_due_at` | DATETIME | 是 | NULL | SLA **响应**截止时间 |
| `first_response_at` | DATETIME | 是 | NULL | 首次接单（响应）时间 |
| `resolved_at` | DATETIME | 是 | NULL | 解决时间 |
| `closed_at` | DATETIME | 是 | NULL | 关闭时间 |
| `cancelled_at` | DATETIME | 是 | NULL | 取消时间 |
| `resolution` | TEXT | 是 | NULL | 解决说明 |
| `cancel_reason` | VARCHAR(500) | 是 | NULL | 取消原因 |
| `created_at` | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `updated_at` | DATETIME | 否 | CURRENT_TIMESTAMP ON UPDATE | 更新时间 |

索引：`PRIMARY(id)`、`UNIQUE uk_tickets_ticket_no(ticket_no)`、`idx_tickets_status(status)`、`idx_tickets_priority(priority)`、`idx_tickets_created_at(created_at)`、`idx_tickets_sla_due_at(sla_due_at)`、`idx_tickets_assignee_id(assignee_id)`。
**`type` 无索引；无 `(assignee_id, created_at)`、`(status, resolved_at)` 组合索引。**

### 2.2 `ticket_events`（`04-ticket-events.sql`）

| 字段 | 类型 | 空 | 含义 |
| --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | 主键 |
| `ticket_id` | BIGINT UNSIGNED | 否 | 工单 id |
| `action` | VARCHAR(24) | 否 | `CREATE/UPDATE/ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL` |
| `from_status` / `to_status` | VARCHAR(16) | 是 | 变更前后状态 |
| `operator_id` | BIGINT UNSIGNED | 是 | 操作人 id |
| `operator_name` | VARCHAR(64) | 否 | 操作人显示名 |
| `note` | VARCHAR(500) | 是 | 说明/原因 |
| `created_at` | DATETIME | 否 | 发生时间 |

索引：`PRIMARY(id)`、`idx_ticket_events_ticket(ticket_id, id)`。无独立 `created_at` 索引；本阶段指标不依赖该表。

### 2.3 `users`（`03-users.sql`）

| 字段 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AUTO_INCREMENT | 主键 |
| `username` | VARCHAR(32) | 否 | — | 登录名（唯一） |
| `password` | VARCHAR(72) | 否 | — | BCrypt 哈希，任何接口不得返回 |
| `name` | VARCHAR(64) | 否 | — | 显示名 |
| `role` | VARCHAR(16) | 否 | `'USER'` | `ADMIN/AGENT/USER` |
| `status` | TINYINT | 否 | 1 | 1 正常 / 0 禁用 |
| `created_at` / `updated_at` | DATETIME | 否 | CURRENT_TIMESTAMP | 创建/更新时间 |

索引：`PRIMARY(id)`、`UNIQUE(username)`。**`role`、`status` 无索引**（表小，可接受）。

### 2.4 `agent_profiles`（`05-agent-dispatch.sql`）

| 字段 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `user_id` | BIGINT UNSIGNED | 否 | — | 主键 = `users.id`，级联删除 |
| `max_concurrent` | INT | 否 | 5 | 最大并发工单数 |
| `availability` | VARCHAR(16) | 否 | `'AVAILABLE'` | `AVAILABLE/BUSY/OFFLINE/DISABLED` |
| `created_at` / `updated_at` | DATETIME | 否 | CURRENT_TIMESTAMP | — |

索引：`PRIMARY(user_id)`、`idx_agent_profiles_availability(availability)`、外键 `fk_agent_profiles_user`。

### 2.5 `agent_skills` / `agent_skill_relations` / `ticket_type_skill_rules`

| 表 | 字段 | 索引/约束 |
| --- | --- | --- |
| `agent_skills` | `id`、`skill_code VARCHAR(32)`、`skill_name VARCHAR(64)`、`description VARCHAR(255)`、`created_at` | `PRIMARY(id)`、`uk_agent_skills_code`、`uk_agent_skills_name` |
| `agent_skill_relations` | `id`、`agent_id BIGINT`、`skill_id BIGINT`、`proficiency_level TINYINT`（1 初级/2 中级/3 高级/4 专家）、`created_at` | `uk_agent_skill_relation(agent_id, skill_id)`、`idx_agent_skill_relation_skill(skill_id)`、外键 agent→users、skill→agent_skills |
| `ticket_type_skill_rules` | `id`、`ticket_type VARCHAR(32)`、`skill_id BIGINT`、`required TINYINT`（1 必需 / 0 加分） | `uk_ticket_type_skill(ticket_type, skill_id)`、`idx_ticket_type_skill_type(ticket_type)` |

种子技能 8 个：`JAVA / DATABASE / NETWORK / SYS_OPS / FRONTEND / SERVER / SOFTWARE_SUPPORT / OTHER`。

### 2.6 `dispatch_logs`（`05-agent-dispatch.sql`）

| 字段 | 类型 | 空 | 含义 |
| --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | 主键 |
| `ticket_id` / `ticket_no` | BIGINT / VARCHAR(32) | 否 | 工单 |
| `action` | VARCHAR(16) | 否 | `RECOMMEND / ASSIGN` |
| `operator_id` | BIGINT UNSIGNED | 是 | 操作人 id |
| `operator_name` | VARCHAR(64) | 否 | 操作人显示名 |
| `agent_id` / `agent_name` | BIGINT / VARCHAR(64) | 是 | 被推荐/被分配人员 |
| `score` | DECIMAL(5,1) | 是 | 推荐或分配时的匹配分 |
| `candidate_count` | INT | 是 | 候选人数 |
| `note` | VARCHAR(500) | 是 | 备注 |
| `detail` | JSON | 是 | 推荐候选快照（结构不承诺，不解析） |
| `created_at` | DATETIME | 否 | 记录时间 |

索引：`idx_dispatch_logs_ticket(ticket_id, id)`、`idx_dispatch_logs_agent(agent_id)`、`idx_dispatch_logs_created(created_at)`。
**`action` 无索引；无耗时字段；无负载前后快照。**

> 实际写入事实（`DispatchService.writeLog`）：`RECOMMEND` 记录调用时传入 `agentId=null, agentName=null`，`score` 取排序后第一名候选分数，`candidate_count` 为返回候选数；只有 `ASSIGN` 记录写入被分配人的 `agent_id/agent_name`。因此“按 `RECOMMEND.agent_id` 分组的人员推荐次数”在现有数据上只能落到空值组。

### 2.7 `ai_analyses`（`20261116-phase5-ai-knowledge.sql`）

| 字段 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AI | 主键 |
| `ticket_id` | BIGINT UNSIGNED | 否 | — | 关联工单（级联删除） |
| `session_id` | BIGINT UNSIGNED | 是 | NULL | 关联会话（删除置 NULL） |
| `analysis_type` | VARCHAR(32) | 否 | — | `TICKET_ANALYSIS/PRIORITY/SOLUTION/DISPATCH/CHAT` |
| `status` | VARCHAR(16) | 否 | `'PENDING'` | `PENDING/SUCCESS/FAILED` |
| `requested_by` | BIGINT UNSIGNED | 否 | — | 发起人 `users.id` |
| `request_note` | VARCHAR(500) | 是 | NULL | 请求备注 |
| `prompt_version` / `model` | VARCHAR(64) | 是 | NULL | 提示词版本 / 模型名 |
| `latency_ms` | INT | 是 | NULL | 调用耗时（毫秒） |
| `ticket_snapshot` | JSON | 是 | NULL | 分析时工单与 SLA 快照 |
| `result_json` | JSON | 是 | NULL | 结构化结果 |
| `error_code` / `error_message` | VARCHAR(64) / VARCHAR(500) | 是 | NULL | 失败信息 |
| `created_at` | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `completed_at` | DATETIME | 是 | NULL | 完成时间 |

索引：`idx_ai_analyses_ticket(ticket_id, id)`、`idx_ai_analyses_user(requested_by, id)`、`idx_ai_analyses_type(analysis_type, created_at)`；外键 ticket→tickets、session→ai_sessions。
**无 `(status, created_at)`、无单独 `created_at` 索引。**

### 2.8 `ai_suggestions`（同上迁移）

| 字段 | 类型 | 空 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `id` | BIGINT UNSIGNED | 否 | AI | 主键 |
| `analysis_id` / `ticket_id` | BIGINT UNSIGNED | 否 | — | 分析记录 / 工单 |
| `suggestion_type` | VARCHAR(32) | 否 | — | `CLASSIFICATION/PRIORITY/SOLUTION` |
| `status` | VARCHAR(16) | 否 | `'PENDING'` | `PENDING/ACCEPTED/REJECTED/EXECUTED` |
| `payload` | JSON | 否 | — | 建议结构（不解析内容做统计） |
| `summary` | VARCHAR(500) | 否 | — | 摘要 |
| `created_by` | BIGINT UNSIGNED | 否 | — | 创建人 |
| `decided_by` | BIGINT UNSIGNED | 是 | NULL | 决策人 |
| `decided_at` | DATETIME | 是 | NULL | 决策时间 |
| `decision_note` / `execution_note` | VARCHAR(500) | 是 | NULL | 决策/执行备注 |
| `created_at` / `updated_at` | DATETIME | 否 | CURRENT_TIMESTAMP | — |

索引：`UNIQUE uk_ai_suggestion_analysis_type(analysis_id, suggestion_type)`、`idx_ai_suggestions_ticket(ticket_id, status)`。
**无 `(status, created_at)` 索引。**

### 2.9 `ai_sessions` / `ai_messages` / `kb_articles` / `kb_ticket_links`

| 表 | 关键字段 | 索引 |
| --- | --- | --- |
| `ai_sessions` | `id, ticket_id, user_id, title VARCHAR(120), status VARCHAR(16) DEFAULT 'ACTIVE'（ACTIVE/ARCHIVED）, created_at, updated_at` | `idx_ai_sessions_user(user_id, updated_at)`、`idx_ai_sessions_ticket(ticket_id, id)`、外键 |
| `ai_messages` | `id, session_id, role VARCHAR(16)（USER/ASSISTANT/SYSTEM）, content TEXT, analysis_id, created_at` | `idx_ai_messages_session(session_id, id)`、外键 |
| `kb_articles` | `id, title VARCHAR(200)（唯一）, category VARCHAR(32), problem TEXT, solution MEDIUMTEXT, tech_stack VARCHAR(200), keywords VARCHAR(255), source_ticket_id, status（PUBLISHED/ARCHIVED）, created_by, updated_by, created_at, updated_at` | `uk_kb_articles_title`、`idx_kb_articles_category`、`idx_kb_articles_updated`、外键 |
| `kb_ticket_links` | `id, article_id, ticket_id, linked_by, note VARCHAR(255), created_at` | `uk_kb_ticket_link(article_id, ticket_id)`、`idx_kb_ticket_links_ticket(ticket_id)`、外键 |

本阶段只对上述表做读取/计数，不写入、不改结构。

---

## 3. 枚举清单（Java 枚举与 SQL 注释一致）

| 枚举 | 值 | 来源 |
| --- | --- | --- |
| 工单状态 `TicketStatus` | `PENDING` 待分配、`ASSIGNED` 已分配、`ACCEPTED` 已接单、`PROCESSING` 处理中、`RESOLVED` 已解决、`CLOSED` 已关闭、`CANCELLED` 已取消 | `ticket/enums/TicketStatus.java`；`terminal()` = `CLOSED/CANCELLED` |
| 优先级 `Priority` | `LOW`、`MEDIUM`、`HIGH`、`URGENT` | `ticket/enums/Priority.java` |
| 工单类型 `TicketType` | `INCIDENT` 故障申报、`SERVICE_REQUEST` 服务请求、`CHANGE` 变更申请、`CONSULT` 业务咨询 | `ticket/enums/TicketType.java` |
| SLA 状态 `SlaState` | `ON_TRACK`、`WARNING`、`OVERDUE`、`COMPLETED` | `ticket/enums/SlaState.java` |
| 调度动作 `DispatchAction` | `RECOMMEND`、`ASSIGN` | `dispatch/enums/DispatchAction.java` |
| 人员可用状态 `AgentAvailability` | `AVAILABLE`、`BUSY`、`OFFLINE`、`DISABLED` | `dispatch/enums/AgentAvailability.java` |
| 负载等级 `LoadLevel` | `LOW`、`NORMAL`、`HIGH`、`FULL`；阈值默认 `<=49 / <=79 / <=99 / 其余` | `dispatch/enums/LoadLevel.java`、`DispatchProperties.Load` |
| AI 分析类型 `AnalysisType` | `TICKET_ANALYSIS`、`PRIORITY`、`SOLUTION`、`DISPATCH`、`CHAT` | `intelligence/enums/AnalysisType.java` |
| AI 分析状态 `AnalysisStatus` | `PENDING`、`SUCCESS`、`FAILED` | `intelligence/enums/AnalysisStatus.java` |
| AI 建议类型 `SuggestionType` | `CLASSIFICATION`、`PRIORITY`、`SOLUTION` | `intelligence/enums/SuggestionType.java` |
| AI 建议状态 `SuggestionStatus` | `PENDING`、`ACCEPTED`、`REJECTED`、`EXECUTED` | `intelligence/enums/SuggestionStatus.java` |
| 工单事件 `TicketEventType` | `CREATE/UPDATE/ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL` | `ticket/enums/TicketEventType.java` |
| 工作流动作 `TicketAction` | `ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL` | `ticket/enums/TicketAction.java` |
| 用户角色 `UserRole` | `ADMIN`、`AGENT`、`USER` | `auth/enums/UserRole.java` |

会话状态 `ACTIVE/ARCHIVED`、知识库状态 `PUBLISHED/ARCHIVED`、知识库分类 `NETWORK/SYSTEM/DATABASE/SECURITY/SOFTWARE/ACCOUNT/HARDWARE/OTHER` 仅存在于 SQL 注释与业务表，无 Java 枚举，本阶段不做枚举校验。

---

## 4. SLA 真实逻辑（必须复用，不得另立口径）

| 项 | 事实 |
| --- | --- |
| 策略常量 | `TicketSlaService.WARNING_MINUTES = 120`；`hours(priority)`：`URGENT=4`、`HIGH=8`、`MEDIUM=24`、`LOW=72` 小时 |
| 截止时间 | `dueAt(createdAt, priority) = createdAt.plusHours(hours)`；落库字段 `tickets.sla_due_at`；`evaluate()` 优先用 `sla_due_at`，为空时按 `created_at + hours(priority)` 现算 |
| 解析兜底 | `parsePriority()` 非法值回落 `MEDIUM`（不抛异常），与迁移脚本 `ELSE 72` 的兜底一致 |
| 完成判定 | `completed = status ∈ {RESOLVED, CLOSED, CANCELLED}`；**取消也算“停止计时”**，SLA 状态直接 `COMPLETED` |
| 完成时间 | `resolved_at` → `closed_at` → `cancelled_at` 依次取值 |
| 剩余分钟 | `ceilSeconds((完成时间或 now) - dueAt) / 60`，向上取整 |
| 状态判定 | 完成 → `COMPLETED`；剩余 < 0 → `OVERDUE`；剩余 ≤ 120 → `WARNING`；否则 `ON_TRACK`（完成态不参与超时判定） |
| 进度百分比 | `elapsedPercent = clamp(round((endAt-createdAt)/(dueAt-createdAt)*100), 0, 100)`，总时长 ≤ 0 时返回 100 |
| 响应 vs 解决 | **数据库没有独立的“解决时限”字段**；`sla_due_at` 是响应截止时间。任何“解决达标”只能作为同一截止时间下的代理指标，页面必须注明 |
| 既有负载口径 | `WorkloadRepository` 用 `COALESCE(resolved_at, closed_at) <= sla_due_at` 作为 `slaAchieved`，即“完成时间达标”，**不是响应达标**；本阶段不复用该口径作为响应达标率 |

---

## 5. 数据缺口（决定指标可算边界）

| # | 缺口 | 影响 |
| --- | --- | --- |
| 1 | `tickets` 无独立“工单分类”字段 | 分类分析只能基于 `type`，页面标题须写“工单类型（项目无独立分类字段）”；`type` 为空归 `UNCLASSIFIED`（历史数据 `type` 为 NOT NULL，实际几乎不会触发） |
| 2 | 无独立“解决 SLA”字段 | 解决达标率是代理口径，不可宣称独立解决 SLA |
| 3 | `dispatch_logs` 无调度耗时字段 | 只能统计“推荐→确认分配间隔”，并注明非算法耗时 |
| 4 | `dispatch_logs` 无历史负载快照 | 调度前后负载变化不可计算，只能返回 `loadChangeAvailable=false` |
| 5 | `dispatch_logs.detail` 为 JSON，结构不承诺 | 约定不解析 `detail`，技能匹配只能用“当前技能配置”而非调度时快照 |
| 6 | `RECOMMEND` 行 `agent_id/agent_name` 为空 | 人员维度推荐次数/采纳数无法直接分组；采纳判定只能基于“该工单后续是否存在 ASSIGN” |
| 7 | `ai_analyses` 无模型准确率真值 | 只统计调用成功率、耗时、错误码；不计算“建议正确率/准确率” |
| 8 | `ai_suggestions.payload` 为 JSON | 只按 `status/suggestion_type` 统计，不解析建议内容质量 |
| 9 | `tickets.assignee_id` 允许 NULL | 历史自由文本处理人不进入人员绩效分母；`assignee_id IS NULL` 的工单在人员维度不可归属 |
| 10 | `latency_ms` 仅成功/失败时写入 | 平均耗时统计必须限定 `status IN ('SUCCESS','FAILED') AND latency_ms IS NOT NULL` |
| 11 | `prompt_version`、`model`、`error_code` 可为 NULL | 错误码分组需归 `UNKNOWN`；不按模型维度做结论 |
| 12 | `ticket_events` 无独立时间索引 | 本阶段不使用事件表做聚合，避免额外扫描 |

---

## 6. 前端现状

### 6.1 菜单与路由

- 菜单双处维护：`src/constants/menu.ts`（`MENU_GROUPS`，含 `soon/desc/points`）与 `src/router/index.ts`（`realRoutes` + 由 `MENU_GROUPS` 自动生成的 `soonRoutes`）。
- “数据分析”组当前 3 个占位项，均 `soon: true`，由 `ComingSoonView` 渲染：
  - 工单趋势 `/analytics/trend`（`chart`）
  - 处理效率 `/analytics/efficiency`（`gauge`）
  - 人员绩效 `/analytics/performance`（`award`）
- 另有 `soon: true` 的“用户管理”“操作日志”；本阶段只替换“数据分析”组，不动其他占位。
- 路由守卫：无 token 跳 `/login`；已登录访问 `/login` 跳 `/`；`afterEach` 设置 `document.title`。

### 6.2 可复用组件（现有 props/事件）

| 组件 | 能力 |
| --- | --- |
| `BaseChart.vue` | `option: EChartsCoreOption`、`height=280`；`ResizeObserver` 自适应；`watch option` 以 `notMerge` 重设；仅注册 line/bar/pie |
| `ChartCard.vue` | `title`、`subtitle`、`hint`、`loading`、`bodyClass`（`flush` 贴合表格）；`#actions` 插槽；内置 skeleton 与 `hint` 口径说明位 |
| `StatCard.vue` | `label`、`value`、`icon`、`desc`、`tone`（brand/cyan/amber/emerald/rose/violet）、`pending`（显示“待接入”）、`loading` |
| `DataTable.vue` | `columns: TableColumn[]`、`rows`、`rowKey='id'`、`loading`、`emptyText`、`clickable`、`dense`、`sortKey`、`sortDir`、`rowClass`；事件 `row-click`、`sort`；`#cell-<key>` 插槽；列 `sortable/align/width/nowrap` |
| `EmptyState.vue` | `icon`、`title`、`desc`、`compact`；默认插槽放操作 |
| `LoadingState.vue` | `text`、`height`（默认 120） |
| 其他 | `AppIcon.vue`、`PriorityTag.vue`、`StatusTag.vue`、`ServiceHealth.vue` |

### 6.3 设计令牌（`src/style.css`）

`--brand #4f46e5`、`--brand-hover #4338ca`、`--brand-weak #eef2ff`、`--cyan #06b6d4`、`--amber #f59e0b`、`--emerald #10b981`、`--rose #ef4444`、`--violet #8b5cf6`；`--ink-1 #0f172a`、`--ink-2 #334155`、`--ink-3 #64748b`、`--ink-4 #94a3b8`；`--bg #f6f7fb`、`--panel #fff`、`--line #e7eaf0`、`--line-soft #f1f4f9`；`--radius-card 14px`、`--radius-ctrl 8px`；`--shadow-card`、`--shadow-hover`、`--shadow-pop`。

### 6.4 第一阶段驾驶舱已有内容（本阶段不得复制）

`DashboardView.vue`：欢迎区（时段问候、日期、角色、今日新增/待分配/处理中）、6 张 KPI 卡（工单总量、今日新增、待分配、处理中、已完成、超时工单）、7/30/90 天“新增/已完成/处理中”三线趋势、状态环图+占比列表、优先级横向条形图、类型环图、最近工单表、我的待办、服务健康。数据来自 `composables/useTicketStore`（前端内存聚合），不是后端统计接口。

### 6.5 请求封装

`src/api/request.ts`：`request<T>(url, options)` 统一加 `Content-Type: application/json` 与 `Authorization: Bearer <token>`；`401` 清登录态并跳登录；非 2xx 抛 `Error(body.message)`；返回 `body.data`。**当前没有文件下载方法**（第六阶段需新增 `downloadFile`）。

---

## 7. 当前数据量与展示约束

实测当前库数据量很小：`tickets` 21 行、`dispatch_logs` 4 行、`ai_analyses` 9 行、`ai_suggestions` 4 行。由此：

- 大多数图表在默认 30 天区间内会出现 0 值或极少样本，必须提供 `EmptyState` 与“样本不足”提示，不得用示例数据填充。
- 人员样本普遍 `assignedTotal < 5`，综合绩效排名大概率全部标记 `lowSample=true`（排名置后且不给出有效名次）。
- 调度 `recommendationCount` 远小于 10，采纳率只能作为样例展示并提示“样本不足，结论仅供参考”。
- AI 成功率/采纳率分母极小，分母为 0 时必须返回 `null` 而不是 0%。

---

## 8. 性能风险（逐条）

| # | 现状 | 影响 | 结论 |
| --- | --- | --- | --- |
| 1 | `ai_analyses` 仅有 `(analysis_type, created_at)` 等索引，无 `(status, created_at)` 与单独 `created_at` | 不按类型筛选的区间统计（总数/成功率/耗时/错误码）走全表扫描 | 需新增 `(status, created_at)`；仍不满足纯时间过滤时接受小表全扫 |
| 2 | `ai_suggestions` 仅有 `(ticket_id, status)` 与 `(analysis_id, suggestion_type)` 唯一键 | `status + created_at` 统计无法命中前导列 | 需新增 `(status, created_at)` |
| 3 | `dispatch_logs` 仅 `created_at` 单列索引，`action` 无索引 | `action='RECOMMEND'` + 时间范围需回表过滤 | 需新增 `(action, created_at)` |
| 4 | `tickets` 仅 `assignee_id` 单列索引 | 人员绩效按 `assignee_id + created_at` 组合过滤选择性差 | 需新增 `(assignee_id, created_at)` |
| 5 | `tickets` 无 `(status, resolved_at)` | “区间内解决”按 `status + resolved_at` 统计只能用 `status` 单列索引再过滤时间 | 需新增 `(status, resolved_at)` |
| 6 | `tickets.type` 无索引 | 类型筛选（`ticketType` 参数）全表扫描 | 当前 21 行可接受；不新增索引，避免无依据扩展 |
| 7 | 完成/超时计算大量使用 `COALESCE(resolved_at, closed_at)`、`TIMESTAMPDIFF(...)`、`DATE(created_at)` | 表达式使索引失效，只能过滤后计算 | 先限制在区间行内计算；`DATE()` 分组在数据量小时可接受 |
| 8 | `users` 无 `role/status` 索引 | 人员集合查询全表扫描 | 表小，不处理 |
| 9 | `dispatch_logs.detail` 为 JSON | 若解析会引入不可控开销且结构无契约 | 约定不解析 |
| 10 | 统计接口可能一次聚合多组指标 | 单请求多 SQL 串行 | 报表类接口限制明细 10000 行；不做跨请求缓存 |

> 索引补充方案见 `docs/phase6/spec.md` 5.1（迁移脚本 `deploy/migrations/20261116-phase6-analytics-indexes.sql`），本文只记录风险依据。

# ServiceFlow AI 第六阶段：数据分析与运营管理中心 — 规格说明

> 状态：待评审。评审通过后才进入编码。
> 本文是决策完整的实现规格：包含现状审计结论、指标口径、接口契约、文件级改动、验证步骤与风险。
> 执行阶段会据此产出用户要求的 `docs/phase6-audit.md`、`docs/phase6-metrics-design.md`、`docs/phase6-api-design.md`、`docs/phase6-test-report.md`。

---

## 1. 现状审计结论（已读取真实代码与建表 SQL）

### 1.1 技术栈与运行约定

| 项 | 现状 |
| --- | --- |
| 前端 | Vue 3 + Vite + TS；hash router；ECharts 6.1（`BaseChart.vue` 按需注册 line/bar/pie） |
| 后端 | Spring Boot 3.5.x + JdbcTemplate，包结构 `com.serviceflow.ai.<domain>.*` |
| 数据库 | MySQL 8.4，库 `serviceflow` |
| 响应 | `ApiResponse<T>{code,message,data}`；分页 `PageResponse<T>` |
| 认证 | `AuthInterceptor` 保护 `/api/**`；`UserContext.getRequired()` 返回 `LoginUser(id,username,name,role)`；角色 `ADMIN/USER/AGENT` |
| 异常 | `ForbiddenException(403)/NotFoundException(404)/ConflictException(409)/IllegalArgumentException(400)` 由 `GlobalExceptionHandler` 统一转换 |

### 1.2 可用业务表（字段以建表 SQL 为准）

**tickets**（`deploy/init/02-tickets.sql` + phase3 迁移）
- 字段：`id, ticket_no, title, description, type, priority, status, creator, creator_id, assignee, assignee_id, sla_due_at, first_response_at, resolved_at, closed_at, cancelled_at, resolution, cancel_reason, created_at, updated_at`
- 索引：`status`、`priority`、`created_at`、`sla_due_at`、`assignee_id`、唯一 `ticket_no`
- 状态枚举：`PENDING/ASSIGNED/ACCEPTED/PROCESSING/RESOLVED/CLOSED/CANCELLED`
- 优先级：`LOW/MEDIUM/HIGH/URGENT`
- 类型：`INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT`
- **没有独立的“工单分类”字段**：本阶段“分类分析”只能基于 `type`，页面必须标注为“工单类型（项目无独立分类字段）”，不得虚构分类。

**ticket_events**：`id,ticket_id,action,from_status,to_status,operator_id,operator_name,note,created_at`；索引 `(ticket_id,id)`。

**agent_profiles**：`user_id,max_concurrent,availability,created_at,updated_at`。
**agent_skills / agent_skill_relations / ticket_type_skill_rules**：技能字典、人员技能（1-4 级）、类型→技能规则（`required=1/0`）。

**dispatch_logs**：`id,ticket_id,ticket_no,action(RECOMMEND/ASSIGN),operator_id,operator_name,agent_id,agent_name,score,candidate_count,note,detail(JSON),created_at`；索引 `(ticket_id,id)`、`agent_id`、`created_at`。

**ai_analyses**：`id,ticket_id,session_id,analysis_type,status(PENDING/SUCCESS/FAILED),requested_by,request_note,prompt_version,model,latency_ms,ticket_snapshot,result_json,error_code,error_message,created_at,completed_at`；索引 `(ticket_id,id)`、`(requested_by,id)`、`(analysis_type,created_at)`。
**ai_suggestions**：`id,analysis_id,ticket_id,suggestion_type(CLASSIFICATION/PRIORITY/SOLUTION),status(PENDING/ACCEPTED/REJECTED/EXECUTED),payload,summary,created_by,decided_by,decided_at,decision_note,execution_note,created_at,updated_at`；唯一 `(analysis_id,suggestion_type)`，索引 `(ticket_id,status)`。
**ai_sessions / ai_messages / kb_articles / kb_ticket_links**：会话、消息、知识库；本阶段只做统计，不写入。

### 1.3 SLA 真实逻辑（必须复用）

`TicketSlaService`：
- 仅一套 SLA 截止时间：`sla_due_at = created_at + hours(priority)`，`URGENT=4 / HIGH=8 / MEDIUM=24 / LOW=72` 小时。
- 评估状态：`ON_TRACK / WARNING(剩余≤120分钟) / OVERDUE(剩余<0) / COMPLETED`。
- 完成时间优先取 `resolved_at → closed_at → cancelled_at`。
- **项目没有独立的“解决时限”字段**：SLA 策略是“响应时限”。因此任何“解决达标”只能作为同截止时间的代理指标，页面必须注明，不能宣称存在独立解决 SLA。
- `WorkloadRepository` 当前用 `COALESCE(resolved_at,closed_at) <= sla_due_at` 表示达成，本阶段不复用该口径作为“响应达标”，避免概念混淆。

### 1.4 前端现状

- 菜单双处维护：`src/constants/menu.ts` + `src/router/index.ts`。
- “数据分析”组当前有 3 个 `soon: true` 占位项（工单趋势/处理效率/人员绩效），由 `ComingSoonView` 渲染。
- 已有可复用组件：`BaseChart`、`ChartCard`、`StatCard`、`DataTable`、`EmptyState`、`LoadingState`、`AppIcon`、`PriorityTag`、`StatusTag`。
- 设计令牌：`--brand #4f46e5`、`--bg #f6f7fb`、`--panel #fff`、`--line`、`--ink-1..4`、`--radius-card`、`--shadow-card`。
- 第一阶段驾驶舱 `DashboardView.vue` 已有：KPI 卡、7/30/90 天新增/已完成/处理中趋势、状态环图、优先级条形图、类型环图、最近工单。**本阶段不得复制这些内容**。

### 1.5 数据与性能风险

1. `ai_analyses` 无单独 `created_at` 索引（现有索引前导列是 `ticket_id/requested_by/analysis_type`），按时间范围聚合会扫描较多行。
2. `ai_suggestions` 仅有 `(ticket_id,status)`，按 `status + created_at` 统计无合适索引。
3. `dispatch_logs` 的 `action` 无索引，按 `action + created_at` 统计无合适索引。
4. `tickets` 人员统计按 `assignee_id + created_at` 组合筛选，现有 `assignee_id` 单列索引可用但不理想。
5. `dispatch_logs` 无“调度耗时”字段；`detail` JSON 结构不能假设，不解析。
6. `dispatch_logs` 无历史负载快照，无法计算“调度前后负载变化”。

---

## 2. 目标与非目标

### 2.1 目标
- 新增独立 `analytics` 后端模块，全部指标由 MySQL SQL 聚合，前端只展示。
- 新增 6 个分析页面：工单运营、SLA 效率、人员绩效、智能调度、AI 效果、综合报表中心。
- 所有指标写明分子/分母/统计范围；空数据返回空数组与 0/null，不填充假数据。
- 所有统计接口仅 `ADMIN/AGENT` 可访问；`USER` 返回 403。
- 报表导出与页面筛选一致，使用 CSV（UTF-8 BOM，Excel 可直接打开），不新增依赖。

### 2.2 非目标
- 不引入 Redis/ClickHouse/Elasticsearch/数据仓库/定时快照表（当前数据量与需求不构成理由）。
- 不新增“工单分类”字段，不伪造分类数据。
- 不计算不存在的数据：模型准确率、调度算法耗时、历史负载变化。
- 不修改前五阶段业务表结构与既有接口行为。

---

## 3. 统一查询与口径

### 3.1 查询参数（所有统计接口）

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `startDate` | `yyyy-MM-dd` | 否 | 默认 `endDate-29天` |
| `endDate` | `yyyy-MM-dd` | 否 | 默认今天 |
| `ticketType` | enum | 否 | `INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT` |
| `priority` | enum | 否 | `LOW/MEDIUM/HIGH/URGENT` |
| `assigneeId` | long | 否 | 处理人员 `users.id` |

校验：
- `startDate > endDate` → 400「开始日期不能晚于结束日期」。
- 跨度 > 366 天 → 400「统计范围不能超过 366 天」。
- 非法枚举 → 400。
- 时间边界：`[startDate 00:00:00, endDate+1 00:00:00)`，`created_at` 按此过滤。

### 3.2 术语
- **区间内创建**：`created_at ∈ [from, to)`。
- **区间内解决**：`status='RESOLVED' AND resolved_at ∈ [from,to)`。
- **区间内关闭**：`status='CLOSED' AND closed_at ∈ [from,to)`。
- **取消工单**：`status='CANCELLED'`；计入总量/状态分布，**不计入任何 SLA 与绩效分母**。
- **完成工单**：`status IN ('RESOLVED','CLOSED')`；完成时间 `COALESCE(resolved_at,closed_at)`。
- **当前占用**：实时 `status IN ('ASSIGNED','ACCEPTED','PROCESSING')`，不受日期范围影响。

---

## 4. 指标定义（决策完整）

### 4.1 工单运营

| 指标 | 公式 | 范围/说明 |
| --- | --- | --- |
| 工单总数 | `COUNT(*) WHERE created_at∈区间` | 含所有状态 |
| 新增工单 | 同工单总数 | 与第一阶段“今日新增”不同，这里是区间总量 |
| 已解决 | `COUNT(*) WHERE status='RESOLVED' AND resolved_at∈区间` | 按解决事件时间 |
| 已关闭 | `COUNT(*) WHERE status='CLOSED' AND closed_at∈区间` | 按关闭事件时间 |
| 处理中 | 实时 `COUNT(*) WHERE status IN ('ASSIGNED','ACCEPTED','PROCESSING')` | 不受区间影响，卡片标注“当前” |
| 已取消 | `COUNT(*) WHERE status='CANCELLED' AND cancelled_at∈区间` | 按取消事件时间 |
| 每日新增 | 按 `DATE(created_at)` 分组计数 | 缺失日期补 0（真实为 0，非伪造） |
| 每日解决 | 按 `DATE(resolved_at)` 分组，`status='RESOLVED'` | 同上 |
| 每日关闭 | 按 `DATE(closed_at)` 分组，`status='CLOSED'` | 同上 |
| 类型分布 | 区间内创建按 `type` 分组；`type` 为空归 `UNCLASSIFIED` | 页面标题注明“工单类型（无独立分类字段）” |
| 状态分布 | 区间内创建工单的**当前**状态分布 | 标题注明“区间内创建工单的当前状态” |
| 优先级-总数 | 区间内创建按 `priority` 分组 | — |
| 优先级-解决数 | 上述分组中 `status IN ('RESOLVED','CLOSED')` | — |
| 优先级-超时数 | 非取消，且 `COALESCE(完成时间, NOW()) > sla_due_at` | 含“已完成但超时”和“当前已超时” |
| 优先级-平均处理时长 | `AVG(TIMESTAMPDIFF(MINUTE, created_at, 完成时间))`，仅完成工单 | 分钟，保留 1 位 |
| 高频问题类型 | 按类型计数降序 | 不做 NLP 聚类，不虚构问题标签 |

### 4.2 SLA 效率

**响应达标（主口径）**
- 分母 `D_resp`：非取消 + `sla_due_at IS NOT NULL` + 区间内创建 + （`first_response_at IS NOT NULL` 或 `NOW() > sla_due_at`）。
  - 含义：已响应，或截止时间已过仍未响应的工单。
- 分子 `N_resp`：`D_resp` 中 `first_response_at IS NOT NULL AND first_response_at <= sla_due_at`。
- `响应达标率 = N_resp / D_resp`，分母为 0 时返回 `null`。
- 页面必须展示分子、分母与“已到期/已响应”口径说明。

**解决达标（代理口径）**
- 分母 `D_resolve`：非取消 + `sla_due_at IS NOT NULL` + 区间内创建 + `status IN ('RESOLVED','CLOSED')` + 完成时间非空。
- 分子 `N_resolve`：完成时间 `<= sla_due_at`。
- `解决达标率 = N_resolve / D_resolve`。
- 页面注明：项目 SLA 为响应时限，此指标为同一截止时间下的解决代理指标，非独立解决 SLA。

**其他**
| 指标 | 公式 |
| --- | --- |
| SLA 总体达标率 | 采用响应达标率，字段名 `overallRate`，并附 `overallNumerator/overallDenominator` |
| 首次响应达标率 | `N_resp/D_resp`（按优先级/类型/人员筛选后） |
| 预警数量 | 当前活动工单，`0 <= TIMESTAMPDIFF(MINUTE,NOW(),sla_due_at) <= 120` |
| 超时数量 | 当前活动工单，`NOW() > sla_due_at` |
| 历史超时完成 | 完成工单且完成时间 `> sla_due_at`（单独字段 `overdueCompleted`） |
| 平均首次响应时长 | `AVG(TIMESTAMPDIFF(MINUTE,created_at,first_response_at))`，非空响应，非取消，区间内创建 |
| 平均解决时长 | `AVG(TIMESTAMPDIFF(MINUTE,created_at,完成时间))`，完成工单 |
| SLA 记录缺失 | 非取消 + 区间内创建 + `sla_due_at IS NULL`，单独计数，不进入任何分母 |

**SLA 趋势**：按区间内创建日期分组，每组返回响应达标率与样本数；样本数 < 5 的日期前端以浅色点/提示“样本不足”。
**SLA 状态分布**：区间内创建的非取消工单，按当前状态分为 `ON_TRACK/WARNING/OVERDUE/COMPLETED`（与 `TicketSlaService` 一致）。
**时长分布桶**：`<60`、`60-240`、`240-480`、`480-1440`、`1440-4320`、`>4320` 分钟；分别统计首次响应与解决。
**优先级对比**：每个优先级的 `D_resp/N_resp/达标率/平均首响/当前超时`。

### 4.3 人员绩效

人员集合：`users.role='AGENT'`（含禁用账号，展示账号状态），左连 `agent_profiles` 取 `max_concurrent`。
工单归属：`tickets.assignee_id = users.id`。

| 指标 | 公式/说明 |
| --- | --- |
| 累计处理工单 | 区间内创建且分配给该人员、非取消的工单数 `assignedTotal`（历史累计工作量） |
| 已解决工单 | 上述中 `status IN ('RESOLVED','CLOSED')` 的 `resolvedTotal` |
| 当前处理中 | 实时 `ASSIGNED/ACCEPTED/PROCESSING` 数量 `currentOccupied`（当前工作负载） |
| 工单完成率 | `resolvedTotal / assignedTotal`，分母 0 → null |
| 平均首次响应 | 有 `first_response_at` 的区间工单平均分钟 |
| 平均解决时长 | 完成工单平均分钟 |
| SLA 响应达标率 | 与 4.2 相同的 `N_resp/D_resp`，按人员过滤 |
| 当前负载 | `currentOccupied / max_concurrent * 100`（保留 1 位） |
| 超时工单数 | 区间工单中非取消且 `COALESCE(完成时间,NOW()) > sla_due_at` |
| 优先级加权完成量 | 完成工单按 `URGENT=4/HIGH=3/MEDIUM=2/LOW=1` 求和 |
| 综合绩效分 | 见下 |
| 样本不足 | `assignedTotal < 5` → `lowSample=true`，不参与有效排名（排名置后并显示“样本不足”） |

**综合绩效分（0-100，保留 1 位）**
```
slaScore      = COALESCE(响应达标率, 0)                       // 0-100
completion    = COALESCE(完成率, 0)                            // 0-100
volumeScore   = maxWeightedResolved == 0 ? 0
              : 优先级加权完成量 / maxWeightedResolved * 100   // 当前筛选结果内归一化
loadHealth    = max(0, 100 - 当前负载率)                       // 0-100
score = 0.5*slaScore + 0.2*completion + 0.2*volumeScore + 0.1*loadHealth
```
- 该公式同时考虑 SLA、完成率、优先级与当前负载；页面展示四个分项，避免黑盒。
- 负载为实时快照，页面注明“排名会随当前负载变化”。
- 排序：`lowSample=false` 按 `score DESC, assignedTotal DESC`；`lowSample=true` 排在最后且 `rank=null`。
- 排行榜支持前端按各列排序（`DataTable` 的 `sortable`），默认综合分降序。

**绩效趋势** `GET /agents/trend`：按日返回 `resolved`、`avgFirstResponseMinutes`、`responseSlaRate`；可选 `agentId`，不传则全团队汇总。

### 4.4 智能调度

| 指标 | 公式/说明 |
| --- | --- |
| 推荐次数 | 区间内 `action='RECOMMEND'` 的行数 |
| 推荐成功（被采纳） | 工单级：取该工单区间内最后一条 `RECOMMEND`；其后存在同 `agent_id` 的 `ASSIGN` → 采纳 |
| 人工确认分配次数 | 区间内 `ASSIGN` 且该工单此前存在 `RECOMMEND` |
| 推荐采纳率 | `采纳工单数 / 已决策推荐工单数`；已决策 = 推荐后出现任意 `ASSIGN`（同人=采纳，不同人=拒绝） |
| 推荐拒绝率 | `拒绝工单数 / 已决策推荐工单数` |
| 待确认推荐 | 有 `RECOMMEND` 但无后续 `ASSIGN`；**不计入采纳率分母** |
| 平均确认间隔 | `AVG(TIMESTAMPDIFF(SECOND, 推荐时间, 同工单 ASSIGN 时间))`；标注“推荐→确认分配间隔，非算法耗时” |
| 人员推荐次数 | 按 `RECOMMEND.agent_id` 分组，附采纳/拒绝 |
| 技能匹配分布 | 对区间内 `ASSIGN` 工单：按 `ticket_type_skill_rules.required=1` 检查被分配人员**当前**技能；全有=MATCHED，部分=PARTIAL，全无=UNMATCHED，无规则=UNKNOWN。注明“基于当前技能配置，非调度时快照” |
| 优先级调度效果 | 有推荐的工单按 `tickets.priority` 分组，计算采纳率与样本数 |
| 调度前后负载变化 | **不可用**：无历史负载快照；返回 `loadChangeAvailable=false` 与说明，前端显示空状态，不虚构 |
| 样本不足 | `recommendationCount < 10` → `lowSample=true`，页面提示“样本不足，结论仅供参考” |

### 4.5 AI 效果

分析记录（区间内 `created_at`）：
| 指标 | 公式 |
| --- | --- |
| 调用总数 | `COUNT(*)` |
| 成功/失败/待处理 | 按 `status` 计数 |
| 成功率 | `SUCCESS / (SUCCESS + FAILED)`；PENDING 不计入分母 |
| 平均响应耗时 | `AVG(latency_ms)`，仅 `SUCCESS/FAILED` 且 `latency_ms IS NOT NULL` |
| 功能分布 | 按 `analysis_type` 分组：总数/成功/失败/成功率/平均耗时 |
| 常见错误类型 | `FAILED` 按 `error_code` 分组降序，前 10；空值归 `UNKNOWN` |

建议记录（区间内 `created_at`）：
| 指标 | 公式 |
| --- | --- |
| 建议总数 | `COUNT(*)` |
| 待确认 | `status='PENDING'` |
| 已采纳 | `status IN ('ACCEPTED','EXECUTED')` |
| 已拒绝 | `status='REJECTED'` |
| 已决策 | `已采纳 + 已拒绝`（不含待确认） |
| 建议采纳率 | `已采纳 / 已决策`；分母 0 → null |
| 分类型采纳 | 按 `suggestion_type` 分组计算同上 |

明确：`AI 调用成功率` 与 `建议采纳率` 是两个独立指标、两张独立图表；模型返回成功不等于建议正确，不计算“建议正确率”。

### 4.6 综合报表

报表类型：
- `TICKET_OPERATIONS` 工单运营汇总
- `SLA_EFFICIENCY` SLA 效率汇总
- `AGENT_PERFORMANCE` 人员绩效明细
- `DISPATCH_EFFECT` 智能调度效果
- `AI_EFFECT` AI 效果汇总

统一响应：
```json
{
  "reportName": "工单运营汇总",
  "generatedAt": "2026-11-16T10:30:00",
  "range": {"startDate":"2026-10-18","endDate":"2026-11-16","label":"2026-10-18 ~ 2026-11-16"},
  "filters": {"ticketType":null,"priority":null,"assigneeId":null,"assigneeName":null},
  "metrics": [{"key":"total","label":"工单总数","value":128,"formula":"区间内创建的工单数"}],
  "detailColumns": [{"key":"ticketNo","label":"工单编号"}],
  "detailRows": [{"ticketNo":"SF...","status":"RESOLVED"}],
  "notes": ["取消工单不计入 SLA 分母"]
}
```
导出：同一服务方法生成数据，CSV 首部写报表名、时间范围、筛选条件、生成时间、字段说明，空行后写明细；UTF-8 BOM；`Content-Type: text/csv;charset=UTF-8`；`Content-Disposition: attachment; filename="phase6-<type>-<yyyyMMdd>.csv"`；最多 10000 行，超出在 `notes` 说明。

---

## 5. 后端设计（文件级）

新增包 `com.serviceflow.ai.analytics`：

```
controller/AnalyticsController.java
service/AnalyticsSecurity.java
service/TicketAnalyticsService.java
service/SlaAnalyticsService.java
service/AgentAnalyticsService.java
service/DispatchAnalyticsService.java
service/AiAnalyticsService.java
service/ReportService.java
repository/AnalyticsRepository.java
dto/AnalyticsQuery.java
dto/OverviewResponse.java
dto/TrendResponse.java
dto/TicketDistributionResponse.java
dto/SlaOverviewResponse.java
dto/SlaTrendResponse.java
dto/AgentPerformanceResponse.java
dto/AgentTrendResponse.java
dto/DispatchOverviewResponse.java
dto/AiOverviewResponse.java
dto/AiSuggestionStatsResponse.java
dto/ReportResponse.java
```

约定：
- Controller 只做 `@RequestParam` 绑定、构造 `AnalyticsQuery`、调用 service、`ApiResponse.ok`；不写 SQL。
- `AnalyticsSecurity.requireViewer()`：`ADMIN/AGENT` 放行，否则 `ForbiddenException("仅管理员或处理人员可查看运营分析")`。所有 service 公开方法首行调用。
- `AnalyticsQuery` 紧凑构造器完成默认值、日期顺序、跨度、枚举校验；提供 `from()/toExclusive()`。
- `AnalyticsRepository` 使用 JdbcTemplate + `?` 占位符；筛选条件用白名单字段拼接，参数全部绑定；禁止接收表名/列名/SQL 片段。
- 百分比统一 `round1`，分母 0 返回 `null`；计数返回 `long`。
- 空数据返回空数组 + 0/null，不抛异常。

接口清单：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/analytics/overview` | 工单总量卡片 |
| GET | `/api/analytics/tickets/trend` | 每日新增/解决/关闭 |
| GET | `/api/analytics/tickets/distribution` | 类型/状态/优先级分布与优先级时长 |
| GET | `/api/analytics/sla/overview` | SLA 指标、状态分布、时长桶、优先级对比 |
| GET | `/api/analytics/sla/trend` | 每日响应达标率与样本 |
| GET | `/api/analytics/agents/performance` | 人员绩效明细 + 排名 + 分项分 |
| GET | `/api/analytics/agents/ranking` | 仅排名（复用 performance 计算） |
| GET | `/api/analytics/agents/trend` | 绩效趋势（可选 agentId） |
| GET | `/api/analytics/dispatch/overview` | 调度全部指标 |
| GET | `/api/analytics/ai/overview` | AI 调用统计 |
| GET | `/api/analytics/ai/suggestions` | 建议采纳统计 |
| GET | `/api/analytics/reports` | 报表 JSON（type 必填） |
| GET | `/api/analytics/reports/export` | CSV 导出（同参数） |

所有接口权限：`ADMIN/AGENT`；`USER` → 403。日期参数格式错误 → 400（Spring 类型转换异常需在 `GlobalExceptionHandler` 增加 `MethodArgumentTypeMismatchException` 处理，返回 400 而不是 500）。

### 5.1 索引迁移（有明确依据，非破坏性）

新增 `deploy/migrations/20261116-phase6-analytics-indexes.sql`（幂等）：
- `tickets (assignee_id, created_at)`：人员区间统计。
- `tickets (status, resolved_at)`：解决趋势。
- `ai_analyses (status, created_at)`：AI 调用时间统计。
- `ai_suggestions (status, created_at)`：建议状态时间统计。
- `dispatch_logs (action, created_at)`：推荐/分配统计。

执行后用 `EXPLAIN` 验证关键查询命中索引；未命中则在测试报告中记录原因。

---

## 6. 前端设计（文件级）

新增：
```
src/api/analytics.ts
src/composables/useAnalyticsRange.ts
src/components/DateRangeFilter.vue
src/views/analytics/TicketOperationsView.vue
src/views/analytics/SlaEfficiencyView.vue
src/views/analytics/AgentPerformanceView.vue
src/views/analytics/DispatchAnalyticsView.vue
src/views/analytics/AiAnalyticsView.vue
src/views/analytics/ReportCenterView.vue
```
修改：
- `src/constants/menu.ts`：「数据分析」组替换为 6 个真实项：
  - 工单运营分析 `/analytics/tickets` icon `chart`
  - SLA 效率分析 `/analytics/sla` icon `clock`
  - 人员绩效分析 `/analytics/agents` icon `award`
  - 智能调度分析 `/analytics/dispatch` icon `route`
  - AI 效果分析 `/analytics/ai` icon `sparkles`
  - 综合报表中心 `/analytics/reports` icon `logs`
- `src/router/index.ts`：新增 6 条真实路由（移除对应 soon 占位）。
- `src/api/request.ts`：新增 `downloadFile(url, filenameFallback)`，携带 Bearer token，处理错误 JSON，成功后用 `URL.createObjectURL` 触发下载。

页面共性：
- `page` 容器 + 页头（标题/副标题/`DateRangeFilter`）。
- 指标卡 `StatCard`；图表 `ChartCard` + `BaseChart`；表格 `DataTable`。
- 三态：加载 `LoadingState`/卡片 skeleton；空数据 `EmptyState`；错误红色提示条（复用驾驶舱 `.alert` 风格）。
- 每张图有标题、副标题/统计范围、`hint` 口径说明。

页面差异化图表（避免同质化）：
| 页面 | 图表 |
| --- | --- |
| 工单运营 | 三线趋势、状态环图、类型横向条形+占比表、优先级双轴/组合图、优先级明细表 |
| SLA | 达标率进度条组、SLA 状态环图、首响/解决时长分布柱状图、优先级达标率分组柱状图、趋势线（带样本不足标记） |
| 人员绩效 | 排行榜横向柱状图 + 可排序明细表、负载分布（按等级堆叠/条形）、个人趋势线（选择人员） |
| 智能调度 | 采纳/拒绝/待确认环图、采纳率趋势（按日）、人员推荐次数横向柱状图、优先级效果分组柱状图、技能匹配分布表 |
| AI | 调用成功率与建议采纳率两张独立指标卡+两个独立图表、功能维度分组柱状图、错误码表格、建议类型采纳表 |
| 报表中心 | 筛选表单、指标摘要卡、明细表、导出按钮 |

报表导出：前端把当前筛选值原样传给导出接口；下载文件名优先取 `Content-Disposition`。导出后提示“已按当前筛选条件导出”。

---

## 7. 测试与验收

### 7.1 后端自动化测试（不依赖真实 MySQL）
- `AnalyticsQueryTest`：默认 30 天；start>end→400；>366 天→400；非法枚举→400；边界 `from/toExclusive`。
- `SlaAnalyticsServiceTest`：分母 0 → null；取消不计入（由仓库 SQL 保证，服务测试验证映射）；缺失 SLA 单独计数；预警/超时映射。
- `AiAnalyticsServiceTest`：成功率排除 PENDING；采纳率排除 PENDING；拒绝不误算。
- `DispatchAnalyticsServiceTest`：待确认不计入采纳率分母；样本不足标记。
- `AgentAnalyticsServiceTest`：完成率分母 0；低样本不参与排名；综合分四项权重与归一化。
- `ReportServiceTest`：JSON 与 CSV 使用同一数据源；CSV 含报表名/时间范围/筛选/指标/明细；BOM 正确。
- `AnalyticsControllerTest`（MockMvc standalone，沿用现有测试写法）：200、400、403；日期格式错误→400。
- 运行：`$env:JAVA_HOME='D:\JDK21'; .\mvnw.cmd -B package`，要求全部通过。

### 7.2 前端
- `node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
- `node .\node_modules\vite\bin\vite.js build`
- 浏览器逐页验收：登录 → 6 个页面 → 日期预设/自定义 → 多条件筛选 → 图表与表格一致 → 未来日期空状态 → 导出下载 → 权限 403 提示。

### 7.3 数据一致性
- 随机抽取 3 张工单人工核对状态/时间戳。
- 对比 `/api/analytics/overview` 与 `SELECT COUNT(*)`。
- 对比 SLA 分子/分母与手工 SQL。
- 校验取消/关闭工单不计入 SLA 分母。
- 校验边界日期 `startDate=endDate` 与跨天。

### 7.4 交付文档
- `docs/phase6-audit.md`
- `docs/phase6-metrics-design.md`
- `docs/phase6-api-design.md`
- `docs/phase6-test-report.md`（区分已通过/未通过/未测试；无真实数据时如实标注）

---

## 8. 风险与待确认

1. **浏览器验收需要可登录账号**：若种子管理员密码不可知，需用户提供；否则该项在测试报告标记“未测试（缺少凭据）”。
2. **“工单分类”不存在**：已按 `type` 实现并显式标注，不新增字段。
3. **“解决 SLA”不存在独立字段**：采用代理口径并注明，避免误导。
4. **调度耗时/负载变化不可得**：明确返回不可用，不用其他数据替代。
5. **AI 建议正确性无真值**：只统计采纳率，不宣称准确率。
6. 统计查询在真实数据量下的性能：先 `EXPLAIN`，必要时仅补索引，不引入缓存/数仓。

---

## 9. 评审确认项

请确认以下决策后再进入编码：
1. 统计接口权限采用 `ADMIN/AGENT`，`USER` 返回 403。
2. “工单分类分析”使用现有 `type`，页面标注“无独立分类字段”。
3. “解决达标率”作为响应 SLA 截止时间下的代理指标并注明。
4. 调度耗时采用“推荐→确认分配间隔”，不宣称算法耗时；负载变化显示不可用。
5. 报表导出采用 UTF-8 BOM CSV，不引入 Excel 依赖。
6. 新增 5 个统计索引迁移脚本。

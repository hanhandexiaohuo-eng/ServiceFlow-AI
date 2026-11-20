# 第六阶段接口设计：数据分析与运营管理中心

> 生成依据：实际读取的建表 SQL（`deploy/init/02-tickets.sql`、`03-users.sql`、`05-agent-dispatch.sql`、`deploy/migrations/20261116-phase3-workflow.sql`、`20261116-phase4-agent-dispatch.sql`、`20261116-phase5-ai-knowledge.sql`）与 Java 源码（`common/dto/ApiResponse`、`GlobalExceptionHandler`、`TicketSlaService`、`WorkloadRepository`、`DispatchService`、AI 实体与枚举）。
> 接口清单、权限与导出约定与 `docs/phase6/spec.md` 第 5 节一致，字段名来自真实表列，不引入新表、新列、新枚举。

---

## 1. 通用约定

### 1.1 路径与响应

| 项 | 约定 |
| --- | --- |
| 前缀 | 全部为 `GET /api/analytics/**`；由 `AuthInterceptor` 保护，必须携带 `Authorization: Bearer <token>` |
| 成功响应 | `ApiResponse<T>{code:200, message:"success", data:T}`；唯一例外是 CSV 导出（见 1.6） |
| 失败响应 | `ApiResponse<Void>{code:<HTTP 状态码>, message:"<可读原因>", data:null}` |
| 空数据 | `data` 返回空数组 / 0 / `null`，HTTP 仍为 200，不抛异常、不返回 404 |
| 编码 | UTF-8；JSON `Content-Type: application/json` |
| 日期格式 | `yyyy-MM-dd`（如 `2026-11-16`）；响应时间戳 `yyyy-MM-ddTHH:mm:ss` |
| 时区 | 服务端本地时区（部署为 Asia/Shanghai）；日期边界按 `startDate 00:00:00` ~ `endDate+1 00:00:00` |

### 1.2 通用查询参数（§2 全部接口适用，报表接口另加 `type`）

| 参数 | 类型 | 必填 | 默认值 | 校验 |
| --- | --- | --- | --- | --- |
| `startDate` | `yyyy-MM-dd` | 否 | `endDate - 29 天` | 必须能解析为日期，否则 400；不得晚于 `endDate` |
| `endDate` | `yyyy-MM-dd` | 否 | 今天 | 必须能解析为日期，否则 400 |
| `ticketType` | enum | 否 | 不筛选 | 仅 `INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT`，非法 → 400 |
| `priority` | enum | 否 | 不筛选 | 仅 `LOW/MEDIUM/HIGH/URGENT`，非法 → 400 |
| `assigneeId` | long | 否 | 不筛选 | 必须为整数，类型转换失败 → 400；不校验用户是否存在，无数据返回空数组 |

校验顺序（`AnalyticsQuery` 紧凑构造器）：先补默认值 → 解析日期 → `startDate > endDate` → 跨度 > 366 天 → 枚举白名单。
时间口径：`created_at ∈ [startDate 00:00:00, endDate+1 00:00:00)`；“处理中/当前负载”为实时值，不受区间影响。

### 1.3 权限

| 角色 | 结果 |
| --- | --- |
| `ADMIN` | 放行 |
| `AGENT` | 放行 |
| `USER` | `403`，`message = "仅管理员或处理人员可查看运营分析"` |
| 未登录/Token 失效 | `401`（`AuthInterceptor`） |

实现方式：`AnalyticsSecurity.requireViewer()`，每个 service 公开方法首行调用；Controller 不自行判断角色。

### 1.4 异常映射

| HTTP | 触发条件 | `message` 示例 |
| --- | --- | --- |
| 400 | `startDate` 晚于 `endDate` | `开始日期不能晚于结束日期` |
| 400 | 区间跨度 > 366 天 | `统计范围不能超过 366 天` |
| 400 | 非法 `ticketType` / `priority` | `非法工单类型` / `非法优先级`（与 `AnalyticsQuery.normalizeEnum` 一致） |
| 400 | 日期格式非法 | `日期格式应为 yyyy-MM-dd`（Controller 以 `String` 接收并解析，不依赖 MVC 类型转换） |
| 400 | `assigneeId` 类型转换失败 | `参数格式不正确：assigneeId`（`MethodArgumentTypeMismatchException`，需在 `GlobalExceptionHandler` 增加处理器，避免落到 500） |
| 400 | 报表 `type` 缺失或非法 | `缺少报表类型` / `不支持的报表类型：XXX` |
| 403 | `USER` 角色 | `仅管理员或处理人员可查看运营分析` |
| 401 | 未携带/无效 Token | 由 `AuthInterceptor`/`UnauthorizedException` 返回 |
| 500 | 未预期异常 | `服务器内部错误`（服务端记录完整堆栈，不向前端泄露） |

### 1.5 安全约定（无任意 SQL 入口）

- 后端**不接受**任何表名、列名、排序字段名、SQL 片段、`where` 表达式、`group by` 表达式作为参数。
- 所有 SQL 使用 `JdbcTemplate` + `?` 占位符；动态条件只允许在白名单字段（`created_at / type / priority / assignee_id / status / action / analysis_type / suggestion_type`）内拼接固定片段，参数全部绑定。
- 未知查询参数一律忽略，不参与拼接；不使用 `@RequestParam Map<String,String>` 透传。
- 报表导出只接受 1.2 的参数与枚举 `type`，不提供自定义字段选择。

### 1.6 CSV 导出约定

| 项 | 约定 |
| --- | --- |
| 编码 | UTF-8 **带 BOM**（`\uFEFF` 前缀），Excel 可直接打开不乱码 |
| `Content-Type` | `text/csv;charset=UTF-8` |
| `Content-Disposition` | `attachment; filename="phase6-<type>-<yyyyMMdd>.csv"`，如 `phase6-TICKET_OPERATIONS-20261116.csv` |
| 内容顺序 | 报表名 → 时间范围 → 筛选条件 → 生成时间 → 字段说明 → 空行 → 明细表头 → 明细行 |
| 上限 | 明细最多 10000 行；超出时在文件头部说明“明细已截断，仅导出前 10000 行”，接口不做分页下载 |
| 数据一致性 | 与 `/api/analytics/reports` 使用同一服务方法与同一份指标计算，不重复写 SQL |
| 失败 | 参数校验失败发生在写流之前，仍返回 JSON `ApiResponse{code:400,...}`；已开始写流后的异常由容器处理 |

---

## 2. 接口明细（13 个）

### 2.1 `GET /api/analytics/overview` — 工单总量卡片

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| `startDate` / `endDate` / `ticketType` / `priority` / `assigneeId` | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `total` | long | 区间内创建的工单总数；“新增工单”卡片复用此字段 |
| `resolved` | long | 区间内解决：`status='RESOLVED' AND resolved_at∈区间` |
| `closed` | long | 区间内关闭：`status='CLOSED' AND closed_at∈区间` |
| `processing` | long | **实时**活动工单：`ASSIGNED/ACCEPTED/PROCESSING` |
| `cancelled` | long | 区间内取消：`status='CANCELLED' AND cancelled_at∈区间` |

- 统计口径：除 `processing` 外均按事件时间落在区间；取消工单计入 `total/cancelled`，不进 SLA 与绩效分母。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：400（日期非法/顺序/跨度/枚举/类型转换）、401、403、500。

### 2.2 `GET /api/analytics/tickets/trend` — 每日新增/解决/关闭

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `points` | array | 按日升序，长度 = 区间天数 |
| `points[].date` | string | `yyyy-MM-dd` |
| `points[].created` | long | 当日新增（`DATE(created_at)`） |
| `points[].resolved` | long | 当日解决（`DATE(resolved_at)` 且 `status='RESOLVED'`） |
| `points[].closed` | long | 当日关闭（`DATE(closed_at)` 且 `status='CLOSED'`） |

- 统计口径：逐日补 0 与 `date` 一一对应；无数据日期是真实 0，不是插值或伪造。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.3 `GET /api/analytics/tickets/distribution` — 类型/状态/优先级分布

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `byType` | array | 类型分布，按数量降序 |
| `byType[].type` | string | `INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT/UNCLASSIFIED`（前端映射中文名） |
| `byType[].count` | long | 数量 |
| `byType[].percent` | number \| null | 占类型总数比例（0–100，1 位）；总数 0 → null |
| `byStatus` | array | 区间内创建工单的**当前**状态分布 |
| `byStatus[].status` | string | 7 个状态之一，前端用 `StatusTag` 映射中文名 |
| `byStatus[].count` / `byStatus[].percent` | long / number \| null | 数量 / 占状态总数比例 |
| `byPriority` | array | 固定 4 行，顺序 `URGENT/HIGH/MEDIUM/LOW`，缺失补 0 |
| `byPriority[].priority` | string | 优先级 |
| `byPriority[].total` | long | 该优先级工单数 |
| `byPriority[].resolved` | long | 其中 `RESOLVED/CLOSED` 数量 |
| `byPriority[].overdue` | long | 非取消且 `COALESCE(完成时间,NOW()) > sla_due_at` |
| `byPriority[].avgHandleMinutes` | number \| null | 完成工单平均处理时长（分钟，1 位） |

- 统计口径：类型/状态/优先级均基于“区间内创建”的工单；状态为当前状态而非历史快照；页面标题需注明“工单类型（项目无独立分类字段）”。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.4 `GET /api/analytics/sla/overview` — SLA 指标总览

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `firstResponse` | object | 响应达标主口径 `{numerator,denominator,rate}`，`rate` 可为 null |
| `firstResponse.numerator` / `denominator` | long | `N_resp` / `D_resp` |
| `firstResponse.rate` | number \| null | `numerator/denominator*100`，1 位 |
| `resolution` | object | 解决达标代理口径 `{numerator,denominator,rate,proxy}` |
| `resolution.numerator` / `denominator` | long | `N_resolve` / `D_resolve` |
| `resolution.rate` | number \| null | 代理达标率 |
| `resolution.proxy` | boolean | 固定 `true`，页面必须注明“代理口径” |
| `overallRate` | number \| null | SLA 总体达标率，等于 `firstResponse.rate` |
| `overallNumerator` / `overallDenominator` | long | 同 `firstResponse` 分子/分母 |
| `warningCount` | long | 当前活动工单剩余 0–120 分钟 |
| `overdueCount` | long | 当前活动工单 `NOW() > sla_due_at` |
| `overdueCompleted` | long | 完成工单且完成时间 `> sla_due_at` |
| `missingSla` | long | 非取消、区间创建、`sla_due_at IS NULL`，不进入任何分母 |
| `avgFirstResponseMinutes` | number \| null | 平均首次响应时长（分钟，1 位） |
| `avgResolutionMinutes` | number \| null | 平均解决时长（分钟，1 位） |
| `statusDistribution` | array | 固定 4 行 `[{state:"ON_TRACK/WARNING/OVERDUE/COMPLETED", count}]` |
| `firstResponseBuckets` | array | 响应时长 6 桶 `[{bucket,count}]`，固定顺序 |
| `resolutionBuckets` | array | 解决时长 6 桶 `[{bucket,count}]` |
| `priorityComparison` | array | 每优先级：`priority, denominator, numerator, rate, avgFirstResponseMinutes, overdue` |
| `notes` | string[] | 固定口径说明（取消不进分母、解决为代理、预警/超时为实时、缺失 SLA 单列） |

- 统计口径：`D_resp` = 非取消 + `sla_due_at` 非空 + 区间创建 +（已响应 或 已过截止时间）；`N_resp` 为其中 `first_response_at <= sla_due_at`。`D_resolve` = 非取消 + 有 SLA + 区间创建 + 已完成 + 完成时间非空；分子为完成时间 `<= sla_due_at`。取消不进任何分母；缺失 SLA 单列，不进分母。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.5 `GET /api/analytics/sla/trend` — 每日响应达标率

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `points` | array | 按日升序，长度 = 区间天数 |
| `points[].date` | string | `yyyy-MM-dd` |
| `points[].numerator` | long | 当日 `N_resp` |
| `points[].denominator` | long | 当日 `D_resp` |
| `points[].rate` | number \| null | `numerator/denominator*100`，分母 0 → null |
| `points[].sampleSufficient` | boolean | `denominator >= 5`；false 时前端提示“样本不足” |

- 统计口径：按 `DATE(created_at)` 分组，逐日使用 2.4 的响应达标公式；缺失日期输出 0/0/null。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.6 `GET /api/analytics/agents/performance` — 人员绩效明细 + 排名

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `maxWeightedResolved` | long | 当前筛选结果内 `weightedResolved` 最大值，综合分归一化基准 |
| `agents` | array | 人员行，按排名规则排序 |
| `agents[].agentId` | long | `users.id` |
| `agents[].name` / `username` | string | 显示名 / 登录名 |
| `agents[].accountStatus` | int | `users.status`：1 正常 / 0 禁用 |
| `agents[].availability` | string | `agent_profiles.availability`；无档案时为默认 `AVAILABLE` |
| `agents[].maxConcurrent` | int | 最大并发，默认 5 |
| `agents[].assignedTotal` | long | 区间内创建、分配给该人、非取消（历史累计工作量） |
| `agents[].resolvedTotal` | long | 上述中 `RESOLVED/CLOSED`（已完成量） |
| `agents[].currentOccupied` | long | **实时**活动工单数（当前负载） |
| `agents[].completionRate` | number \| null | `resolvedTotal/assignedTotal*100` |
| `agents[].avgFirstResponseMinutes` | number \| null | 平均首响 |
| `agents[].avgResolutionMinutes` | number \| null | 平均解决 |
| `agents[].responseSlaRate` | number \| null | 响应达标率 |
| `agents[].responseNumerator` / `responseDenominator` | long | 响应达标分子/分母 |
| `agents[].loadRate` | number | `currentOccupied/maxConcurrent*100`，1 位 |
| `agents[].overdueCount` | long | 区间工单超时数 |
| `agents[].weightedResolved` | long | 优先级加权完成量（4/3/2/1） |
| `agents[].score` | number | 综合绩效分 0–100，1 位 |
| `agents[].scoreParts.slaScore` | number | `COALESCE(responseSlaRate,0)` |
| `agents[].scoreParts.completionScore` | number | `COALESCE(completionRate,0)` |
| `agents[].scoreParts.volumeScore` | number | `maxWeightedResolved=0 ? 0 : weightedResolved/maxWeightedResolved*100` |
| `agents[].scoreParts.loadHealth` | number | `max(0,100-loadRate)` |
| `agents[].lowSample` | boolean | `assignedTotal < 5` |
| `agents[].rank` | int \| null | 有效排名；`lowSample=true` 时为 null 且排在最后 |

- 统计口径：人员集合 `users.role='AGENT'`（含禁用账号）；工单归属 `tickets.assignee_id`；`score = 0.5*slaScore + 0.2*completionScore + 0.2*volumeScore + 0.1*loadHealth`；排序 `lowSample=false` 按 `score DESC, assignedTotal DESC`。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.7 `GET /api/analytics/agents/ranking` — 仅排名

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `ranking` | array | 复用 2.6 的计算结果，仅保留排名所需字段 |
| `ranking[].rank` | int \| null | 有效名次；低样本为 null |
| `ranking[].agentId` | long | 人员 id |
| `ranking[].name` | string | 显示名 |
| `ranking[].score` | number | 综合绩效分 |
| `ranking[].assignedTotal` / `resolvedTotal` | long | 累计工作量 / 已完成量 |
| `ranking[].lowSample` | boolean | 是否样本不足 |

- 统计口径：与 2.6 完全一致（同一服务方法裁剪字段），不重复实现计算逻辑。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.8 `GET /api/analytics/agents/trend` — 绩效趋势

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |
| `agentId` | long | 否 | 不传 = 全团队汇总 | 整数，类型转换失败 → 400 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `agentId` | long \| null | 回显查询人员；团队汇总为 null |
| `points` | array | 按日升序，长度 = 区间天数 |
| `points[].date` | string | `yyyy-MM-dd` |
| `points[].resolved` | long | 当日解决量（`RESOLVED/CLOSED`） |
| `points[].avgFirstResponseMinutes` | number \| null | 当日平均首响 |
| `points[].responseSlaRate` | number \| null | 当日响应达标率 |
| `points[].responseNumerator` / `responseDenominator` | long | 当日响应分子/分母 |
| `points[].lowSample` | boolean | 当日 `responseDenominator < 5` |

- 统计口径：按 `DATE(created_at)` 分组；传 `agentId` 时加 `assignee_id=?`，不传时全团队汇总（不做人均）。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：400（含 `agentId` 类型错误）、401、403、500；`agentId` 不存在返回空序列，不报 404。

### 2.9 `GET /api/analytics/dispatch/overview` — 调度效果

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `recommendationCount` | long | 区间内 `action='RECOMMEND'` 行数 |
| `decidedTickets` | long | 推荐后出现任意 `ASSIGN` 的工单数（采纳率分母） |
| `adoptedTickets` | long | 推荐后分配给了推荐人员的工单数 |
| `rejectedTickets` | long | 推荐后分配给了其他人的工单数 |
| `pendingRecommendations` | long | 有推荐但无后续分配的工单数（不进分母） |
| `adoptionRate` | number \| null | `adoptedTickets/decidedTickets*100` |
| `rejectionRate` | number \| null | `rejectedTickets/decidedTickets*100` |
| `manualAssignCount` | long | 区间内 `ASSIGN` 且该工单此前有 `RECOMMEND` 的次数 |
| `avgConfirmIntervalSeconds` | number \| null | 推荐→确认分配平均间隔（秒），非算法耗时 |
| `agentRecommendations` | array | `[{agentId,agentName,recommendCount,adopted,rejected}]`；现有 `RECOMMEND` 未写 `agent_id`，该维度为空值组 |
| `skillMatches` | array | `[{match:"MATCHED/PARTIAL/UNMATCHED/UNKNOWN",count,ratio}]` |
| `priorityEffects` | array | `[{priority,total,adopted,adoptionRate,lowSample}]` |
| `loadChangeAvailable` | boolean | 固定 `false`（无历史负载快照） |
| `loadChangeNote` | string | 不可用原因说明 |
| `lowSample` | boolean | `recommendationCount < 10` |

- 统计口径：`RECOMMEND`/`ASSIGN` 均按 `dispatch_logs.created_at` 落区间；采纳/拒绝按工单去重；待确认不进采纳率分母；技能匹配用被分配人员**当前**技能对比 `ticket_type_skill_rules.required=1`；不解析 `dispatch_logs.detail`。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.10 `GET /api/analytics/ai/overview` — AI 调用统计

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `total` | long | 区间内 `ai_analyses` 总数 |
| `success` / `failed` / `pending` | long | 按 `status` 计数 |
| `successRate` | number \| null | `success/(success+failed)*100`，PENDING 不进分母 |
| `avgLatencyMs` | number \| null | `AVG(latency_ms)`，仅 `SUCCESS/FAILED` 且非空 |
| `byType` | array | `[{analysisType,total,success,failed,successRate,avgLatencyMs}]`，固定 5 类 |
| `errorCodes` | array | `[{errorCode,count}]`，`FAILED` 按 `error_code` 降序前 10，空值归 `UNKNOWN` |

- 统计口径：全部按 `ai_analyses.created_at ∈ 区间`；不计算“建议正确率”。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.11 `GET /api/analytics/ai/suggestions` — 建议采纳统计

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `total` | long | 区间内 `ai_suggestions` 总数 |
| `pending` | long | `status='PENDING'` |
| `accepted` | long | `status IN ('ACCEPTED','EXECUTED')` |
| `rejected` | long | `status='REJECTED'` |
| `decided` | long | `accepted + rejected` |
| `adoptionRate` | number \| null | `accepted/decided*100`，分母 0 → null |
| `byType` | array | `[{suggestionType,total,pending,accepted,rejected,decided,adoptionRate}]`，固定 3 类 |

- 统计口径：按 `ai_suggestions.created_at ∈ 区间`；采纳率分母只含已决策建议，待确认不稀释结果；`EXECUTED` 计入已采纳但不重复计数。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：同 2.1。

### 2.12 `GET /api/analytics/reports` — 报表 JSON

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| `type` | enum | **是** | — | 仅 `TICKET_OPERATIONS/SLA_EFFICIENCY/AGENT_PERFORMANCE/DISPATCH_EFFECT/AI_EFFECT`；缺失/非法 → 400 |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 返回字段 | 类型 | 含义 |
| --- | --- | --- |
| `reportType` | string | 报表枚举 |
| `reportName` | string | 中文名，如“工单运营汇总” |
| `generatedAt` | string | 生成时间 `yyyy-MM-ddTHH:mm:ss` |
| `range.startDate` / `endDate` | string | 实际生效区间 |
| `range.label` | string | `startDate ~ endDate` |
| `filters.ticketType` / `priority` / `assigneeId` | string/long \| null | 实际生效筛选 |
| `filters.assigneeName` | string \| null | 由 `users.name` 解析，未筛选为 null |
| `metrics` | array | `[{key,label,value,formula}]`，`value` 为数字或 null |
| `detailColumns` | array | `[{key,label}]` |
| `detailRows` | array | 按 `detailColumns` key 组织的对象数组，最多 10000 行 |
| `notes` | string[] | 口径说明；明细被截断时必须有截断说明 |

- 统计口径：5 类报表的指标与明细见 `docs/phase6-metrics-design.md` 第 6 节；指标值复用同一套服务方法，不另写公式。
- 权限：`ADMIN/AGENT`；`USER` → 403。
- 异常：400（`type` 缺失/非法、通用校验）、401、403、500。

### 2.13 `GET /api/analytics/reports/export` — CSV 导出

| 请求参数 | 类型 | 必填 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| `type` | enum | **是** | — | 同 2.12 |
| 通用 5 参数 | 见 1.2 | 否 | 见 1.2 | 见 1.2 |

| 响应 | 内容 |
| --- | --- |
| 状态 | `200 OK` |
| `Content-Type` | `text/csv;charset=UTF-8` |
| `Content-Disposition` | `attachment; filename="phase6-<type>-<yyyyMMdd>.csv"` |
| Body | UTF-8 BOM + 报表名/区间/筛选/生成时间/字段说明 + 空行 + 明细；≤10000 行 |

- 统计口径：与 2.12 使用同一服务方法与同一筛选，保证页面与导出数字一致；前端原样透传当前筛选值。
- 权限：`ADMIN/AGENT`；`USER` → 403（JSON 错误体，不返回空文件）。
- 异常：参数错误在写响应流前返回 400 JSON；导出过程中未预期异常 → 500；不提供断点续传与分片下载。

---

## 3. 接口与页面/指标对照

| 页面 | 使用接口 |
| --- | --- |
| 工单运营分析 | `/overview`、`/tickets/trend`、`/tickets/distribution` |
| SLA 效率分析 | `/sla/overview`、`/sla/trend` |
| 人员绩效分析 | `/agents/performance`、`/agents/ranking`、`/agents/trend` |
| 智能调度分析 | `/dispatch/overview` |
| AI 效果分析 | `/ai/overview`、`/ai/suggestions` |
| 综合报表中心 | `/reports`、`/reports/export` |

统一响应示例（工单总量）：

```json
{ "code": 200, "message": "success", "data": { "total": 21, "resolved": 6, "closed": 5, "processing": 4, "cancelled": 1 } }
```

错误示例（权限不足）：

```json
{ "code": 403, "message": "仅管理员或处理人员可查看运营分析", "data": null }
```

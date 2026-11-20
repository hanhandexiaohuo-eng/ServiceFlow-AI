# 第六阶段指标设计：数据分析与运营管理中心

> 生成依据：实际读取的建表 SQL（`deploy/init/02-tickets.sql`、`03-users.sql`、`05-agent-dispatch.sql`、`deploy/migrations/20261116-phase3-workflow.sql`、`20261116-phase4-agent-dispatch.sql`、`20261116-phase5-ai-knowledge.sql`）与 Java 源码（`TicketSlaService`、`WorkloadRepository`、`DispatchService`、AI 实体与枚举）。所有指标口径与 `docs/phase6/spec.md` 第 4 节一致，字段名均来自真实表。

---

## 0. 统一口径（所有指标共用，不再逐条重复）

| 项 | 规则 |
| --- | --- |
| 时间区间 | `[startDate 00:00:00, endDate+1 00:00:00)`；未传时 `endDate=今天`、`startDate=endDate-29 天`；跨度上限 366 天 |
| 区间内创建 | `tickets.created_at ∈ [from, to)` |
| 区间内解决 | `tickets.status='RESOLVED' AND resolved_at ∈ [from,to)` |
| 区间内关闭 | `tickets.status='CLOSED' AND closed_at ∈ [from,to)` |
| 区间内取消 | `tickets.status='CANCELLED' AND cancelled_at ∈ [from,to)` |
| 完成工单 | `status IN ('RESOLVED','CLOSED')`，完成时间 `COALESCE(resolved_at, closed_at)` |
| 取消工单 | `status='CANCELLED'`；计入总量与状态分布，**不计入任何 SLA 与人员绩效分母** |
| 当前占用 | 实时 `status IN ('ASSIGNED','ACCEPTED','PROCESSING')`，**不受日期区间影响** |
| 百分比 | 统一保留 1 位小数（`round1`）；分母为 0 返回 `null`，不返回 `0%` |
| 计数类型 | 全部返回 `long`；缺失数据返回 0 或空数组，不抛异常、不填充假数据 |
| 低样本门槛 | SLA 趋势单日分母 `<5`、人员 `assignedTotal<5` → 样本不足；调度 `recommendationCount<10` → 样本不足 |
| 筛选维度 | `ticketType`（4 值）、`priority`（4 值）、`assigneeId`（`users.id`）；三者同时生效时取交集 |

---

## 1. 工单运营指标

| 指标 | 业务含义 | 计算公式（分子/分母） | 数据来源表·字段 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| 工单总数 | 区间内新进入系统的工单量 | `COUNT(*)`；无分母 | `tickets.id, created_at` | `created_at ∈ [from,to)`，含全部状态 | 与“新增工单”同一数值，接口只返回一个 `total` 字段，避免双写 |
| 新增工单 | 页面卡片文案，等同工单总数 | 同上 | 同上 | 同上 | 与第一阶段“今日新增”不同：这里是整个区间总量 |
| 已解决 | 区间内发生“解决”动作的工单量 | `COUNT(*)` WHERE `status='RESOLVED' AND resolved_at∈[from,to)` | `tickets.status, resolved_at` | 按解决事件时间，不看创建时间 | 已解决后再关闭的工单只计入“已解决”，不会同时计入“已关闭” |
| 已关闭 | 区间内发生“关闭”动作的工单量 | `COUNT(*)` WHERE `status='CLOSED' AND closed_at∈[from,to)` | `tickets.status, closed_at` | 按关闭事件时间 | 关闭是终态；当前状态为 `CLOSED` 才有 `closed_at` |
| 处理中（当前） | 当前正在处理的工单量（实时快照） | `COUNT(*)` WHERE `status IN ('ASSIGNED','ACCEPTED','PROCESSING')` | `tickets.status` | **实时**，忽略日期区间 | 卡片必须标注“当前”；切换日期不变化 |
| 已取消 | 区间内被取消的工单量 | `COUNT(*)` WHERE `status='CANCELLED' AND cancelled_at∈[from,to)` | `tickets.status, cancelled_at` | 按取消事件时间 | 不计入 SLA 分母与人员完成率分母 |
| 每日新增 | 每日进入系统的工单量趋势 | 按 `DATE(created_at)` 分组计数 | `tickets.created_at` | 区间内逐日，缺失日期补 0 | 补 0 是真实 0，不是伪造数据 |
| 每日解决 | 每日解决量趋势 | 按 `DATE(resolved_at)` 分组，限定 `status='RESOLVED'` | `tickets.status, resolved_at` | 区间内逐日，缺失补 0 | 关闭事件不在该序列中，另设“每日关闭” |
| 每日关闭 | 每日关闭量趋势 | 按 `DATE(closed_at)` 分组，限定 `status='CLOSED'` | `tickets.status, closed_at` | 区间内逐日，缺失补 0 | 与“每日解决”并列展示，不合并 |
| 类型分布 | 区间新增工单的类型构成 | 每个 `type` 计数 / `total` × 100 | `tickets.type` | 区间内创建 | `type` 为空归 `UNCLASSIFIED`；页面标题必须写“工单类型（项目无独立分类字段）” |
| 状态分布 | 区间新增工单**当前**处于各状态的比例 | 每个 `status` 计数 / `total` × 100 | `tickets.status, created_at` | 区间内创建工单的当前状态 | 标题必须写“区间内创建工单的当前状态”，否则会与实时看板混淆 |
| 优先级-总数 | 各优先级工单量 | 每个 `priority` 计数 | `tickets.priority` | 区间内创建 | 固定输出 4 个优先级，缺失显示 0 |
| 优先级-解决数 | 各优先级中已完成数量 | `COUNT(*)` WHERE `status IN ('RESOLVED','CLOSED')`，按 `priority` 分组 | `tickets.priority, status` | 区间内创建 | 完成不要求完成时间落在区间内 |
| 优先级-超时数 | 各优先级中已超时数量（含已完成但超时） | 非取消且 `COALESCE(完成时间, NOW()) > sla_due_at`，按 `priority` 分组 | `tickets.sla_due_at, resolved_at, closed_at, status` | 区间内创建，排除 `CANCELLED` | `sla_due_at IS NULL` 不参与；含“当前已超时”与“完成时已超时”两类 |
| 优先级-平均处理时长 | 各优先级完成工单的平均耗时 | `AVG(TIMESTAMPDIFF(MINUTE, created_at, 完成时间))` | `tickets.created_at, resolved_at, closed_at` | 仅完成工单，区间内创建 | 单位分钟，保留 1 位；无完成工单返回 `null` |
| 高频问题类型 | 类型维度的问题量排序 | 按 `type` 计数降序 | `tickets.type` | 区间内创建 | 不做 NLP 聚类、不生成问题标签；与“类型分布”同一数据的不同排序视图 |

---

## 2. SLA 指标

### 2.1 响应达标率（主口径）

| 项 | 定义 |
| --- | --- |
| 指标名称 | 响应达标率 / 首次响应达标率（同一公式，前者用于总览，后者用于筛选后展示） |
| 业务含义 | 在“已产生响应义务”的工单中，首次响应未超过响应截止时间的比例 |
| 分母 `D_resp` | `COUNT(*)` WHERE `status<>'CANCELLED'` AND `sla_due_at IS NOT NULL` AND `created_at∈[from,to)` AND ( `first_response_at IS NOT NULL` OR `NOW() > sla_due_at` ) |
| 分子 `N_resp` | 上述集合中 `first_response_at IS NOT NULL AND first_response_at <= sla_due_at` |
| 公式 | `N_resp / D_resp`；`round1`；`D_resp=0` → `null` |
| 数据来源 | `tickets.status, sla_due_at, first_response_at, created_at` |
| 统计范围 | 区间内创建、非取消、有 SLA 截止时间 |
| 特殊情况 | ① 取消工单不进分母；② `sla_due_at IS NULL` 不进分母，单独计入“SLA 记录缺失”；③ **未到期且未响应**的工单不进分母（尚未违约，不能算失败）；④ 分母为 0 返回 `null`，页面显示“—”；⑤ 页面必须同时展示分子、分母与口径说明 |
| 接口字段 | `overallRate / overallNumerator / overallDenominator`；明细中同名 `responseRate / responseNumerator / responseDenominator` |

### 2.2 解决达标率（代理口径）

| 项 | 定义 |
| --- | --- |
| 指标名称 | 解决达标率（代理） |
| 业务含义 | 已完成工单中，完成时间未超过**同一响应截止时间**的比例 |
| 分母 `D_resolve` | 非取消 + `sla_due_at IS NOT NULL` + `created_at∈[from,to)` + `status IN ('RESOLVED','CLOSED')` + `COALESCE(resolved_at, closed_at) IS NOT NULL` |
| 分子 `N_resolve` | 上述集合中 `COALESCE(resolved_at, closed_at) <= sla_due_at` |
| 公式 | `N_resolve / D_resolve`；分母 0 → `null` |
| 数据来源 | `tickets.status, sla_due_at, resolved_at, closed_at, created_at` |
| 统计范围 | 区间内创建且已完成、非取消、有 SLA |
| 特殊情况 | ① **项目不存在独立“解决时限”字段**，该指标是响应截止时间下的代理指标，页面必须注明“非独立解决 SLA”；② 复用了 `COALESCE` 口径，但不得与 `WorkloadRepository` 的调度绩效口径混用；③ 分母为 0 → `null` |

### 2.3 其他 SLA 指标

| 指标 | 业务含义 | 公式（分子/分母） | 数据来源 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| SLA 总体达标率 | 页面主指标，采用响应达标率 | 同 2.1 | 同 2.1 | 同 2.1 | 不混入解决达标；解决达标单列卡片 |
| 预警数量 | 即将到期但未超时的活动工单 | `0 <= TIMESTAMPDIFF(MINUTE, NOW(), sla_due_at) <= 120` 的活动工单计数 | `tickets.status, sla_due_at` | **实时**，仅 `ASSIGNED/ACCEPTED/PROCESSING/PENDING` 等活动态 | 与 `TicketSlaService.WARNING_MINUTES=120` 一致；已完成/已取消不计 |
| 超时数量 | 当前已超时的活动工单 | `NOW() > sla_due_at` 的活动工单计数 | 同上 | **实时**，活动态 | 与 `SlaState.OVERDUE` 判定一致 |
| 历史超时完成 | 已完成但完成时间超过截止时间的工单 | `COUNT(*)` WHERE 完成工单 AND 完成时间 `> sla_due_at` | `tickets.resolved_at, closed_at, sla_due_at` | 区间内创建、非取消 | 字段名 `overdueCompleted`；不并入“超时数量” |
| 平均首次响应时长 | 响应速度均值 | `AVG(TIMESTAMPDIFF(MINUTE, created_at, first_response_at))` | `tickets.created_at, first_response_at` | 区间内创建、非取消、`first_response_at IS NOT NULL` | 单位分钟，保留 1 位；无样本 → `null` |
| 平均解决时长 | 从创建到完成的速度 | `AVG(TIMESTAMPDIFF(MINUTE, created_at, 完成时间))` | `tickets.created_at, resolved_at, closed_at` | 区间内创建、完成工单、非取消 | 单位分钟，保留 1 位；无样本 → `null` |
| SLA 记录缺失 | 无法计算 SLA 的工单量 | `COUNT(*)` WHERE 非取消 AND `created_at∈[from,to)` AND `sla_due_at IS NULL` | `tickets.sla_due_at` | 区间内创建 | 单独展示，**不进入任何分母**；返回字段 `missingSla` |
| SLA 状态分布 | 区间新增工单当前的 SLA 状态构成 | 按 `ON_TRACK/WARNING/OVERDUE/COMPLETED` 分组计数 | `tickets.status, sla_due_at, resolved_at, closed_at, cancelled_at` | 区间内创建、非取消 | `COMPLETED` 含已解决/已关闭（取消已被排除）；判定顺序与 `TicketSlaService.evaluate` 一致 |
| 时长分布桶 | 响应与解决耗时的区间分布 | 每个桶计数 / 对应有效样本数 × 100 | `first_response_at`、完成时间 | 分别统计首次响应与解决 | 桶：`<60`、`60-240`、`240-480`、`480-1440`、`1440-4320`、`>4320` 分钟；空桶显示 0 |
| 优先级对比 | 各优先级的 SLA 表现 | 每个优先级返回 `D_resp / N_resp / 达标率 / 平均首响 / 当前超时` | `tickets.priority` + SLA 字段 | 区间内创建、非取消 | 固定 4 行；某优先级分母 0 → 该行达标率 `null` |

### 2.4 SLA 趋势

| 项 | 定义 |
| --- | --- |
| 指标 | 每日响应达标率 |
| 公式 | 按 `DATE(created_at)` 分组，逐日计算 `N_resp / D_resp` |
| 数据来源 | `tickets.created_at, first_response_at, sla_due_at, status` |
| 统计范围 | 区间内创建、非取消、有 SLA；缺失日期补 0 样本 |
| 特殊情况 | 单日分母 `<5` → 返回 `sampleSufficient=false`，前端标记“样本不足”（浅色点 + 提示），不隐藏该点；分母 0 → 该点 `rate=null`；趋势线不做平滑插值 |

---

## 3. 人员绩效指标

### 3.1 三个工作量的概念区分（必须先讲清）

| 概念 | 字段 | 定义 | 是否受区间影响 |
| --- | --- | --- | --- |
| 当前负载 | `currentOccupied` | 该人员名下 `status IN ('ASSIGNED','ACCEPTED','PROCESSING')` 的工单数 | 否，实时快照 |
| 历史累计工作量 | `assignedTotal` | 区间内创建、`assignee_id=该人员`、非取消的工单数 | 是 |
| 已完成量 | `resolvedTotal` | 上述 `assignedTotal` 中 `status IN ('RESOLVED','CLOSED')` 的数量 | 是 |

### 3.2 绩效指标明细

人员集合：`users.role='AGENT'`（含 `users.status=0` 的禁用账号，需展示账号状态），左连 `agent_profiles` 取 `max_concurrent`（缺省 5）；工单归属 `tickets.assignee_id = users.id`。

| 指标 | 业务含义 | 公式（分子/分母） | 数据来源 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| 累计处理工单 | 区间内分配给该人员的工作量 | `COUNT(*)` WHERE `assignee_id=?` AND `status<>'CANCELLED'` | `tickets.assignee_id, status, created_at` | 区间内创建 | 字段 `assignedTotal`；`assignee_id IS NULL` 的历史工单不计入任何人 |
| 已解决工单 | 上述工作量中已完成的数量 | `COUNT(*)` WHERE 上述 AND `status IN ('RESOLVED','CLOSED')` | 同上 | 区间内创建 | 字段 `resolvedTotal`；不要求完成时间在区间内 |
| 当前处理中 | 当前在手工作负载 | `COUNT(*)` WHERE 活动三态 | `tickets.status, assignee_id` | **实时** | 字段 `currentOccupied`；用于负载率，不参与区间趋势 |
| 工单完成率 | 分配后完成的比例 | `resolvedTotal / assignedTotal × 100` | 同上 | 区间内创建 | 分母 0 → `null`；取消工单已从分子分母同时排除 |
| 平均首次响应 | 该人员响应速度 | `AVG(TIMESTAMPDIFF(MINUTE, created_at, first_response_at))` | `tickets.first_response_at` | 区间内创建、`first_response_at` 非空、非取消 | 无样本 → `null`；分钟保留 1 位 |
| 平均解决时长 | 该人员解决速度 | `AVG(TIMESTAMPDIFF(MINUTE, created_at, 完成时间))` | `tickets.resolved_at, closed_at` | 区间内创建、完成工单 | 无样本 → `null` |
| SLA 响应达标率 | 该人员响应达标情况 | `N_resp / D_resp`（公式同 2.1，附加 `assignee_id=?`） | `tickets.sla_due_at, first_response_at` | 区间内创建、非取消、有 SLA | 分母 0 → `null`；同时返回 `responseNumerator/responseDenominator` |
| 当前负载 | 在手工单占最大并发的比例 | `currentOccupied / max_concurrent × 100` | `tickets.status`、`agent_profiles.max_concurrent` | **实时** | 保留 1 位；`max_concurrent<=0` 按 1 处理；可超过 100% |
| 超时工单数 | 该人员超时工单量 | 非取消且 `COALESCE(完成时间, NOW()) > sla_due_at` | `tickets.sla_due_at` | 区间内创建、该人员 | `sla_due_at IS NULL` 不计 |
| 优先级加权完成量 | 体现完成工单的难度权重 | `Σ(URGENT=4, HIGH=3, MEDIUM=2, LOW=1)`，仅完成工单 | `tickets.priority, status` | 区间内创建、该人员、完成 | 字段 `weightedResolved`；不加权“未完成” |
| 样本不足 | 数据不足以参与有效排名 | `assignedTotal < 5` | `assignedTotal` | 区间内创建 | `lowSample=true`；排名置后且 `rank=null` |

### 3.3 综合绩效分（0–100，保留 1 位）

```
slaScore    = COALESCE(responseSlaRate, 0)                                  // 0–100
completion  = COALESCE(completionRate, 0)                                   // 0–100
volumeScore = maxWeightedResolved == 0 ? 0
            : weightedResolved / maxWeightedResolved * 100                  // 当前筛选结果内归一化
loadHealth  = max(0, 100 - loadRate)                                        // 0–100
score       = 0.5*slaScore + 0.2*completion + 0.2*volumeScore + 0.1*loadHealth
```

| 项 | 规则 |
| --- | --- |
| 归一化基准 | `maxWeightedResolved` = 当前筛选结果内所有人员 `weightedResolved` 的最大值；随筛选变化，不做跨期可比 |
| 分项展示 | 页面同时展示 `slaScore / completion / volumeScore / loadHealth` 四个分项，禁止只给总分 |
| 排名规则 | `lowSample=false` 按 `score DESC, assignedTotal DESC` 排序并给 `rank`；`lowSample=true` 全部排在最后且 `rank=null` |
| 实时影响 | `loadHealth` 依赖实时负载，页面注明“排名会随当前负载变化” |
| 空结果 | 无 `AGENT` 时返回空数组，不报错 |

### 3.4 绩效趋势

| 项 | 定义 |
| --- | --- |
| 指标 | 每日 `resolved`（解决量）、`avgFirstResponseMinutes`（平均首响）、`responseSlaRate`（响应达标率） |
| 公式 | 按 `DATE(created_at)` 分组：`resolved = COUNT(status IN ('RESOLVED','CLOSED'))`；首响与达标率公式同 3.2 |
| 数据来源 | `tickets.created_at, resolved_at, closed_at, first_response_at, sla_due_at, assignee_id` |
| 统计范围 | 区间内创建；可选 `agentId`，不传则全团队汇总 |
| 特殊情况 | 当日无样本 → `null`；单日 `D_resp<5` 标记样本不足；不传 `agentId` 时按团队计算，不做“人均”除法 |

---

## 4. 智能调度指标

### 4.1 推荐与采纳

| 指标 | 业务含义 | 公式（分子/分母） | 数据来源 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| 推荐次数 | 调度推荐被触发的次数 | `COUNT(*)` WHERE `action='RECOMMEND'` | `dispatch_logs.action, created_at` | 区间内记录 | 字段 `recommendationCount`；样本门槛依据它 |
| 推荐成功（被采纳） | 推荐的人被实际分配 | 工单级：取该工单区间内最后一条 `RECOMMEND`，其后存在 `ASSIGN` 即视为已决策；同人 → 采纳 | `dispatch_logs.ticket_id, action, agent_id, created_at` | 区间内记录 | 现有 `RECOMMEND` 行 `agent_id` 为 NULL，无法直接比对“同人”；判定以 `ASSIGN` 行人员为准并如实提示 |
| 人工确认分配次数 | 推荐后由人工确认的分配次数 | `COUNT(*)` WHERE `action='ASSIGN'` AND 该工单此前存在 `RECOMMEND` | `dispatch_logs.ticket_id, action` | 区间内记录 | 没有前置推荐的直接分配不计入 |
| 推荐采纳率 | 已决策推荐中被采纳的比例 | `采纳工单数 / 已决策推荐工单数 × 100` | 同上 | 区间内记录，工单级去重 | 已决策 = 推荐后出现任意 `ASSIGN`；**待确认不计入分母**；分母 0 → `null` |
| 推荐拒绝率 | 已决策推荐中被拒绝的比例 | `拒绝工单数 / 已决策推荐工单数 × 100` | 同上 | 同上 | 拒绝 = 推荐后出现 `ASSIGN` 但分配给了他人；与采纳率互补，二者分母相同 |
| 待确认推荐 | 已推荐但尚未分配的工单量 | `COUNT(工单)` WHERE 有 `RECOMMEND` 且无后续 `ASSIGN` | `dispatch_logs.ticket_id, action` | 区间内记录 | 字段 `pendingRecommendations`；不进入采纳率分母 |
| 平均确认间隔 | 从推荐到人工确认的时长 | `AVG(TIMESTAMPDIFF(SECOND, 推荐时间, 同工单 ASSIGN 时间))` | `dispatch_logs.created_at, action, ticket_id` | 区间内“推荐→分配”配对成功的记录 | 页面标注“推荐→确认分配间隔，非算法耗时”；无配对 → `null` |

### 4.2 人员与技能

| 指标 | 业务含义 | 公式 | 数据来源 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| 人员推荐次数 | 各人员被推荐/被分配的量 | 按 `RECOMMEND.agent_id` 分组计数，附采纳/拒绝 | `dispatch_logs.agent_id, action` | 区间内记录 | **实际实现中 `RECOMMEND` 未写 `agent_id`**，该维度只能得到空值组；需在页面注明“推荐记录未绑定推荐人”，不虚构人员分布 |
| 技能匹配分布 | 被分配人员的技能是否满足类型必需技能 | 对区间内 `ASSIGN` 工单，按 `ticket_type_skill_rules.required=1` 检查被分配人员**当前**技能：全有 `MATCHED`、部分 `PARTIAL`、全无 `UNMATCHED`、该类型无规则 `UNKNOWN` | `dispatch_logs.agent_id, ticket_no/ticket_id`、`tickets.type`、`ticket_type_skill_rules`、`agent_skill_relations` | 区间内 `ASSIGN` | 基于当前技能配置，非调度时快照；`agent_id` 为空 → `UNKNOWN` |
| 优先级调度效果 | 不同优先级下的采纳表现 | 有推荐的工单按 `tickets.priority` 分组，计算采纳率与样本数 | `dispatch_logs`、`tickets.priority` | 区间内创建/推荐 | 每组样本 `<10` 时提示样本不足；分母 0 → `null` |
| 调度前后负载变化 | 评估推荐对负载的影响 | **不可计算** | 无历史负载快照 | — | 固定返回 `loadChangeAvailable=false` 并附说明，前端显示空状态，不得用当前负载替代 |
| 样本不足 | 调度结论可靠性提示 | `recommendationCount < 10` | `dispatch_logs` | 区间内 | `lowSample=true`，页面提示“样本不足，结论仅供参考” |

---

## 5. AI 效果指标

> `AI 调用成功率` 与 `建议采纳率` 是两个独立指标、两张独立图表。模型返回成功不等于建议正确，**不计算“建议正确率/准确率”**。

### 5.1 分析记录（`ai_analyses`，区间内 `created_at`）

| 指标 | 业务含义 | 公式（分子/分母） | 数据来源 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| 调用总数 | AI 分析被调用次数 | `COUNT(*)` | `ai_analyses.id, created_at` | 区间内创建 | 含 `PENDING` |
| 成功数 | 调用成功次数 | `COUNT(*) WHERE status='SUCCESS'` | `ai_analyses.status` | 区间内创建 | — |
| 失败数 | 调用失败次数 | `COUNT(*) WHERE status='FAILED'` | 同上 | 区间内创建 | — |
| 待处理数 | 尚未返回结果的调用 | `COUNT(*) WHERE status='PENDING'` | 同上 | 区间内创建 | 不计入成功率分母 |
| 调用成功率 | 已结束调用的成功占比 | `SUCCESS / (SUCCESS + FAILED) × 100` | `ai_analyses.status` | 区间内创建 | **PENDING 不进分母**；分母 0 → `null` |
| 平均响应耗时 | 调用平均耗时 | `AVG(latency_ms)` | `ai_analyses.latency_ms` | `status IN ('SUCCESS','FAILED') AND latency_ms IS NOT NULL` | 单位毫秒；无样本 → `null`；`PENDING` 无耗时，不参与 |
| 功能分布 | 各分析类型的调用表现 | 按 `analysis_type` 分组：总数/成功/失败/成功率/平均耗时 | `ai_analyses.analysis_type, status, latency_ms` | 区间内创建 | 固定 5 类（`TICKET_ANALYSIS/PRIORITY/SOLUTION/DISPATCH/CHAT`）；无数据类显示 0 |
| 常见错误类型 | 失败原因排行 | `FAILED` 按 `error_code` 分组计数降序，前 10 | `ai_analyses.error_code, status` | 区间内创建 | `error_code` 为空归 `UNKNOWN`；不足 10 类按实际返回 |

### 5.2 建议记录（`ai_suggestions`，区间内 `created_at`）

| 指标 | 业务含义 | 公式（分子/分母） | 数据来源 | 统计范围 | 特殊情况 |
| --- | --- | --- | --- | --- | --- |
| 建议总数 | AI 生成的建议条数 | `COUNT(*)` | `ai_suggestions.id, created_at` | 区间内创建 | 一条分析可产出多条建议（唯一键为 `analysis_id + suggestion_type`） |
| 待确认 | 尚未人工决策的建议 | `COUNT(*) WHERE status='PENDING'` | `ai_suggestions.status` | 区间内创建 | 不参与采纳率分母 |
| 已采纳 | 人工接受或已执行的建议 | `COUNT(*) WHERE status IN ('ACCEPTED','EXECUTED')` | 同上 | 区间内创建 | `EXECUTED` 由 `ACCEPTED` 流转而来，不重复计数 |
| 已拒绝 | 人工拒绝的建议 | `COUNT(*) WHERE status='REJECTED'` | 同上 | 区间内创建 | — |
| 已决策 | 已有人工结论的建议 | `已采纳 + 已拒绝` | 同上 | 区间内创建 | 明确不含 `PENDING` |
| 建议采纳率 | 已决策建议中被接受的比例 | `已采纳 / 已决策 × 100` | `ai_suggestions.status` | 区间内创建 | 分母 0 → `null`；不得用“已采纳/建议总数”，否则待确认会稀释结果 |
| 分类型采纳 | 各建议类型的采纳表现 | 按 `suggestion_type` 分组计算 已采纳/已拒绝/已决策/采纳率 | `ai_suggestions.suggestion_type, status` | 区间内创建 | 固定 3 类（`CLASSIFICATION/PRIORITY/SOLUTION`）；分母 0 → `null` |

---

## 6. 综合报表

| 报表类型 | 名称 | 指标区（`metrics`） | 明细区（`detailColumns/detailRows`） | 必须写入的 `notes` |
| --- | --- | --- | --- | --- |
| `TICKET_OPERATIONS` | 工单运营汇总 | 工单总数、已解决、已关闭、处理中（当前）、已取消、平均解决时长 | 日期、新增、解决、关闭（按日趋势行） | 取消不计入 SLA；处理中为实时值 |
| `SLA_EFFICIENCY` | SLA 效率汇总 | 响应达标率及其分子/分母、解决达标率（代理）及其分子/分母、预警、超时、历史超时完成、平均首响、平均解决、SLA 缺失 | 优先级、响应分母、响应分子、达标率、平均首响、当前超时 | 项目 SLA 为响应时限，解决达标为代理指标；缺失 SLA 不进分母 |
| `AGENT_PERFORMANCE` | 人员绩效明细 | 人员数、总完成量、平均完成率、平均综合分 | 人员、账号状态、累计处理、已解决、当前处理中、完成率、响应达标率、平均首响、平均解决、当前负载、综合分、样本标记 | 低样本（<5）不参与有效排名；负载为实时快照 |
| `DISPATCH_EFFECT` | 智能调度效果 | 推荐次数、已决策、采纳率、拒绝率、待确认、平均确认间隔 | 工单编号、优先级、推荐时间、分配人员、是否采纳、确认间隔（秒） | 待确认不计入采纳率分母；确认间隔非算法耗时；负载变化不可用 |
| `AI_EFFECT` | AI 效果汇总 | 调用总数、调用成功率、平均耗时、建议总数、建议采纳率 | 分析类型、总数、成功、失败、成功率、平均耗时 | 调用成功率与建议采纳率相互独立；不计算建议正确率 |

| 统一字段 | 说明 |
| --- | --- |
| `reportName` | 上表“名称” |
| `generatedAt` | 服务端生成时间（`yyyy-MM-ddTHH:mm:ss`） |
| `range` | `{startDate, endDate, label}`，`label` 形如 `2026-10-18 ~ 2026-11-16` |
| `filters` | `{ticketType, priority, assigneeId, assigneeName}`，未传为 `null`；`assigneeName` 由 `users.name` 解析 |
| `metrics` | `[{key, label, value, formula}]`，`value` 可为数字或 `null` |
| `detailColumns` | `[{key, label}]` |
| `detailRows` | 按列 key 组织的对象数组；最多 10000 行 |
| `notes` | 口径说明与截断说明；超出 10000 行时必须写明“明细已截断，仅导出前 10000 行” |

导出：JSON 与 CSV 由同一服务方法生成，CSV 首部依次写报表名、时间范围、筛选条件、生成时间、字段说明，空行后写明细；UTF-8 BOM；`Content-Type: text/csv;charset=UTF-8`；`Content-Disposition: attachment; filename="phase6-<type>-<yyyyMMdd>.csv"`。

---

## 7. 低样本与空值展示规则汇总

| 场景 | 数据表现 | 前端展示 |
| --- | --- | --- |
| 区间内无工单 | 数组为空、计数 0 | `EmptyState`“所选区间没有工单数据”，不画空坐标轴 |
| 百分比分母 0 | `null` | 显示“—”，不显示 `0%`，并提示“无有效样本” |
| SLA 趋势单日分母 <5 | `rate` 可能非空 | 该点浅色 + “样本不足”提示 |
| 人员 `assignedTotal<5` | `lowSample=true`、`rank=null` | 行尾标签“样本不足”，排在最后 |
| 调度 `recommendationCount<10` | `lowSample=true` | 页面顶部提示“样本不足，结论仅供参考” |
| 调度负载变化 | `loadChangeAvailable=false` + 说明 | 图表位置显示“无历史负载快照，暂不可计算” |
| AI 无 `PENDING` 之外样本 | `successRate=null` | 独立卡片显示“—”，并保留分子分母说明 |
| 未来日期区间 | 全部 0 / 空数组 | 正常空状态，不报错、不补假数据 |

# ServiceFlow AI 第六阶段测试报告（数据分析与运营管理中心）

> 生成时间：2026-11-16
> 本报告只记录实际执行的命令与真实返回结果；无法验证的项目在「未测试项」中如实列出，不做推测性结论。

## 1. 测试环境

| 项 | 版本 / 说明 |
| --- | --- |
| 操作系统 | Windows（本机） |
| JDK | OpenJDK 21.0.11（`D:\JDK21`，项目 target 17） |
| 后端 | Spring Boot 3.5.16，端口 8080，`serviceflow-backend-0.1.0.jar` |
| 数据库 | MySQL 8.4，库 `serviceflow`，本机 3306 |
| 前端 | Vue 3 + Vite，Node v24.16.0，开发端口 5173 |
| AI 服务 | Python FastAPI，端口 8000（本阶段未改动，仅读取其写入的记录） |
| 测试数据规模 | tickets 21、dispatch_logs 4、ai_analyses 9、ai_suggestions 4 |

接口验收方式：后端以进程环境变量启动（数据库与 JWT 密钥仅走环境变量，未写入仓库）；接口用一次性签名令牌调用；浏览器验收使用部署环境变量初始化的管理员账号登录（文档不记录密码）。

## 2. 构建与自动化测试

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量测试 + 打包 | `$env:JAVA_HOME='D:\JDK21'; .\mvnw.cmd -B package` | **通过**（surefire 25 个报告文件，Tests run: 153, Failures: 0, Errors: 0, Skipped: 0） |
| 前端类型检查 | `node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit` | **通过**（退出码 0） |
| 前端生产构建 | `node .\node_modules\vite\bin\vite.js build` | **通过**（退出码 0） |

本阶段新增 7 个测试类共 30 个用例：

| 测试类 | 用例数 | 覆盖点 |
| --- | --- | --- |
| `AnalyticsQueryTest` | 6 | 默认最近 30 天、start>end、366/367 天跨度、非法枚举、大小写归一化、半开区间边界 |
| `SlaAnalyticsServiceTest` | 4 | 分母 0 → null、分子分母映射、缺失 SLA 独立字段、6 桶/4 优先级补零 |
| `AiAnalyticsServiceTest` | 4 | 成功率排除 PENDING、全 PENDING → null、采纳率排除 PENDING、三种建议类型齐全 |
| `DispatchAnalyticsServiceTest` | 3 | 待确认不进分母、同人分配计采纳、推荐人缺失计 unverifiable、低样本、负载变化不可用 |
| `AgentAnalyticsServiceTest` | 4 | 分母 0 → null、综合分公式、低样本 rank=null 且排最后、volume 归零、缺 maxConcurrent |
| `ReportServiceTest` | 5 | 5 类报表生成、CSV BOM/内容/与 JSON 一致、非法类型 |
| `AnalyticsControllerTest` | 4 | 200、默认参数、非法日期 400、USER 403 |

## 3. 接口验收（13 个接口 + 异常路径）

以区间 `2026-10-18 ~ 2026-11-16`（默认最近 30 天）实测：

| 接口 | 结果 | 关键返回 |
| --- | --- | --- |
| `GET /api/analytics/overview` | 200 | total=21, resolved=6, closed=4, cancelled=0, processing=6 |
| `GET /api/analytics/tickets/trend` | 200 | 30 个日期点，缺失日期补 0 |
| `GET /api/analytics/tickets/distribution` | 200 | byType: INCIDENT 10(47.6%) / SERVICE_REQUEST 5 / CHANGE 3 / CONSULT 3；byStatus 含 RESOLVED 6、PENDING 5、PROCESSING 5、CLOSED 4、ASSIGNED 1 |
| `GET /api/analytics/sla/overview` | 200 | firstResponse 0/3=0.0%，resolution 10/10=100.0%（proxy=true），warning=4，overdue=3，overdueCompleted=0，missingSla=0，avgResolution=138.0min，状态分布 ON_TRACK 4 / WARNING 4 / OVERDUE 3 / COMPLETED 10 |
| `GET /api/analytics/sla/trend` | 200 | 按日 points，denominator=0 时 rate=null、sampleSufficient=false |
| `GET /api/analytics/agents/performance` | 200 | 3 名 AGENT，均 lowSample=true、rank=null；agent_ops assigned=1 score=5.0，其余 assigned=0 score=0.0 |
| `GET /api/analytics/agents/ranking` | 200 | items 结构同 performance |
| `GET /api/analytics/agents/trend` | 200 | 按日 points（resolved/avgFirstResponseMinutes/responseSlaRate/denominator） |
| `GET /api/analytics/dispatch/overview` | 200 | recommendationCount=3, accepted=0, rejected=0, pending=2, unverifiable=1, decisionCount=0, adoptionRate=null, lowSample=true, loadChangeAvailable=false |
| `GET /api/analytics/ai/overview` | 200 | totalCalls=9, success=6, failed=3, pending=0, successRate=66.7, avgLatencyMs=5049.0，5 类功能分布 |
| `GET /api/analytics/ai/suggestions` | 200 | total=4, pending=3, accepted=1, rejected=0, decided=1, adoptionRate=100.0，三种类型齐全 |
| `GET /api/analytics/reports?type=TICKET_OPERATIONS` | 200 | reportName/range/filters/metrics/detailColumns/detailRows/notes 齐全 |
| `GET /api/analytics/reports?type=AGENT_PERFORMANCE` | 200 | 人员绩效明细指标与明细行 |
| `GET /api/analytics/reports/export?type=TICKET_OPERATIONS` | 200 | 1395 字节，BOM=true，CSV 首部含报表名、统计范围、筛选条件、生成时间、指标 formula、字段说明、明细 |

异常路径：

| 用例 | 期望 | 实测 |
| --- | --- | --- |
| 非法报表类型 `type=BAD` | 400 | 400 |
| USER 角色访问 `/overview` | 403 | 403 |
| `startDate > endDate` | 400 | 400 |
| 跨度 > 366 天 | 400 | 400 |
| 非法日期 `2026-13-40` | 400 | 400 |
| 非法枚举 `ticketType=FOO` | 400 | 400 |
| 未来区间 `2027-01-01 ~ 2027-01-31` | 200 且全 0 | total=0，趋势全 0（`processing` 为实时值 6，符合口径） |

## 4. 与原始数据 SQL 核对

对同一区间直接查询 MySQL，逐项与接口返回值比对，**全部一致**：

| 指标 | SQL 结果 | 接口结果 |
| --- | --- | --- |
| 区间创建工单 | 21 | 21 |
| 区间解决（RESOLVED + resolved_at） | 6 | 6 |
| 区间关闭（CLOSED + closed_at） | 4 | 4 |
| 当前处理中（ASSIGNED/ACCEPTED/PROCESSING） | 6 | 6 |
| first_response_at 非空 | 0 | 平均首响 null（一致） |
| D_resp / N_resp | 3 / 0 | 3 / 0 |
| D_resolve / N_resolve | 10 / 10 | 10 / 10 |
| 当前预警（0..120 分钟） | 4 | 4 |
| 当前超时 | 3 | 3 |
| AI SUCCESS / FAILED | 6 / 3 | 6 / 3 |
| 建议 总数 / 已采纳 / 待确认 | 4 / 1 / 3 | 4 / 1 / 3 |

状态分布 SQL：ASSIGNED 1、CLOSED 4、PENDING 5、PROCESSING 5、RESOLVED 6，与 distribution 接口一致。

## 5. 索引迁移与执行计划（EXPLAIN）

迁移文件 `deploy/migrations/20261116-phase6-analytics-indexes.sql`，连续执行两次均成功（幂等），共新增 5 个索引：

`tickets(assignee_id,created_at)`、`tickets(status,resolved_at)`、`ai_analyses(status,created_at)`、`ai_suggestions(status,created_at)`、`dispatch_logs(action,created_at)`。

| 查询 | EXPLAIN 结果 |
| --- | --- |
| 人员 + 区间 | `type=ref`，key=`idx_tickets_assignee_id`（rows=1），复合索引为候选 |
| 解决趋势 | `type=ref`，key=`idx_tickets_status`（rows=6），复合索引为候选 |
| AI 调用按状态 | `type=index`，key=`idx_ai_analyses_status_created`，`Using index`（覆盖索引） |
| AI 建议按状态 | `type=index`，key=`idx_ai_suggestions_status_created`，`Using index` |
| 调度动作 | `type=index`，key=`idx_dispatch_logs_action_created`，`Using index` |
| SLA 区间聚合 | `type=ALL`（rows=22） |

说明：当前表仅 22 行，优化器对第 1、2、6 类查询选择单列索引或全表扫描均为最优成本；第 3-5 类已命中覆盖索引。大数量级下的实际收益未验证（见未测试项）。

## 6. 浏览器验收（admin 登录，http://127.0.0.1:5173）

| 页面 | 路由 | 结果 | 证据要点 |
| --- | --- | --- | --- |
| 工单运营分析 | `#/analytics/tickets` | PASS | 指标卡 21/6/4/0/6，折线图、状态环形图、类型条形图、优先级组合图、明细表均渲染，无错误/占位 |
| SLA 效率分析 | `#/analytics/sla` | PASS | 0.0%（0/3）、100.0%（10/10，代理口径）、预警 4、超时 3，四类图表 + 优先级表渲染 |
| 人员绩效分析 | `#/analytics/agents` | PASS | 3 名 AGENT 明细、综合分排行、负载分布、「样本不足」标记、综合分公式说明 |
| 智能调度分析 | `#/analytics/dispatch` | PASS | 推荐 3 / 待确认 2 / 无法判定 1、采纳率显示「-」、样本不足提示、负载变化空状态、技能匹配表 |
| AI 效果分析 | `#/analytics/ai` | PASS | 调用 9/6/3、成功率 66.7%、建议 4/1/3、采纳率 100%，成功率与采纳率为两个独立组件 |
| 综合报表中心 | `#/analytics/reports` | PASS | 生成「工单运营汇总」、30 行明细、导出触发下载并提示「已按当前筛选条件导出」 |

菜单「数据分析」6 项均进入真实页面，无 ComingSoon 占位。

## 7. 回归

- 后端：既有 123 个用例全部通过（含认证、工单、SLA、调度、AI），无行为变更；仅向 `GlobalExceptionHandler` 增加 `MethodArgumentTypeMismatchException → 400`。
- 数据库：未修改任何既有表结构或数据，仅新增 5 个二级索引。
- 前端：除菜单与路由替换 3 个 soon 项、`request.ts` 增加 `downloadFile` 外，未改动前五阶段页面。

## 8. 口径相关实现说明（与规格的差异）

1. **调度「无法判定」**：审计发现历史 `dispatch_logs` 的 `RECOMMEND` 行 `agent_id` 为 NULL，无法与后续 `ASSIGN` 的实际分配人比对。若按「有 ASSIGN 即已决策」会把无法验证的记录判为拒绝，属于虚构结论。实现改为：推荐人缺失且有分配 → 计入 `unverifiableCount`，**不计入采纳率分母**，并在页面与 notes 明示。当前数据下 decisionCount=0、采纳率为 null。
2. **人员综合分**：区间内 `assignedTotal=0` 的人员综合分记 0（否则会因「空闲 → 负载健康分 100」得到 10 分，产生误导）。负载分项仍展示。
3. **解决达标率**：项目只有响应 SLA 截止时间，解决达标为同一截止时间下的代理口径，接口返回 `proxy=true`，页面标注。

## 9. 未测试项（及原因）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 大数据量（10 万+ 工单）性能与索引收益 | 未测试 | 当前库仅 21 工单，无法构造真实规模数据 |
| 取消工单不计入 SLA 分母的线上验证 | 部分覆盖 | 当前库无 CANCELLED 工单，仅由单元测试与 SQL 逻辑保证 |
| 有真实采纳样本的调度采纳率 | 未测试 | 历史 RECOMMEND 均无推荐人，decisionCount=0 |
| 调度前后负载变化 | 不可用 | 系统未保存调度前负载快照，接口明确返回 `loadChangeAvailable=false` |
| AI 分析准确率 | 不可计算 | 无人工标注真值，页面不提供该指标 |
| USER 角色的浏览器端表现 | 未测试 | 仅通过接口验证 403；未用 USER 账号做页面级验收 |
| CSV 在 Microsoft Excel 中打开的实际效果 | 未测试 | 已验证 UTF-8 BOM 与内容，未在 Excel 客户端打开 |
| 调度/技能匹配的时点一致性 | 已知限制 | 技能匹配基于当前技能配置，非调度时快照 |

## 10. 结论

- 13 个统计接口全部可用，权限、日期校验、空数据与异常路径符合规格。
- 页面数字与接口、接口与原始 SQL 三方一致，未发现使用虚构数据的实现。
- 自动化测试、类型检查与生产构建均通过；6 个分析页浏览器验收通过。
- 剩余风险集中在数据规模与历史数据缺失（调度推荐人、负载快照），已在接口字段、页面文案与本文档中明确说明。

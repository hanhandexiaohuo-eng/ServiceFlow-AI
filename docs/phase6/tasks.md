# 第六阶段任务清单（tasks）

> 依据：[spec.md](./spec.md)。按顺序执行，每项完成后更新状态并做对应验证。

## T1 审计文档
- 产出 `docs/phase6-audit.md`：表结构、索引、枚举、SLA 逻辑、调度/AI 字段、前端现状、数据缺口与性能风险。
- 验收：字段与 `deploy/**/*.sql`、Java 源码一致，无假设字段。

## T2 指标设计文档
- 产出 `docs/phase6-metrics-design.md`：逐指标列出名称、业务含义、公式（分子/分母）、数据来源、统计范围、特殊情况。
- 验收：取消/关闭/缺失 SLA、AI 成功率 vs 采纳率、人员三类工作量均有明确口径。

## T3 接口设计文档
- 产出 `docs/phase6-api-design.md`：13 个接口的请求参数、返回字段、统计口径、权限、异常。
- 验收：日期格式与校验、CSV 导出约定、无任意 SQL 入口。

## T4 数据库索引迁移
- 新增 `deploy/migrations/20261116-phase6-analytics-indexes.sql`（幂等，5 个索引）。
- 执行迁移；对 5 类关键查询做 `EXPLAIN`，记录命中情况。
- 验收：迁移可重复执行、不破坏既有数据。

## T5 后端基础设施
- `analytics/dto/AnalyticsQuery.java`：默认区间、顺序/跨度/枚举校验、`from()/toExclusive()`。
- `analytics/service/AnalyticsSecurity.java`：`requireViewer()`。
- `GlobalExceptionHandler` 增加 `MethodArgumentTypeMismatchException` → 400。
- 验收：单元测试覆盖日期边界与非法枚举。

## T6 工单运营后端
- `TicketAnalyticsService` + `AnalyticsRepository` 方法：overview、trend、distribution。
- Controller：`/overview`、`/tickets/trend`、`/tickets/distribution`。
- 验收：空数据返回 0/空数组；日期补零；类型分布含 UNCLASSIFIED 处理。

## T7 SLA 后端
- `SlaAnalyticsService`：overview（响应/解决达标、预警、超时、均值、缺失、状态分布、时长桶、优先级对比）、trend（按日+样本）。
- 验收：`D_resp` 定义严格实现；取消工单不进入分母；分母 0 → null；缺失记录单独统计。

## T8 人员绩效后端
- `AgentAnalyticsService`：performance、ranking、trend。
- 综合分公式：0.5 SLA + 0.2 完成率 + 0.2 优先级加权量 + 0.1 负载健康度；低样本（<5）不参与有效排名。
- 验收：区分当前负载/历史累计/已完成；完成率分母 0；排名排序正确。

## T9 调度后端
- `DispatchAnalyticsService`：推荐/确认/采纳/拒绝/待确认、平均确认间隔、人员推荐、技能匹配、优先级效果。
- 验收：待确认不计入采纳率分母；`loadChangeAvailable=false`；样本 <10 提示。

## T10 AI 后端
- `AiAnalyticsService` + `AiSuggestionStats`：调用统计、成功率、平均耗时、功能分布、错误码、建议状态与采纳率、分类型采纳。
- 验收：成功率排除 PENDING；采纳率 = (ACCEPTED+EXECUTED)/(ACCEPTED+EXECUTED+REJECTED)。

## T11 报表中心后端
- `ReportService`：5 类报表 JSON + CSV 导出（同一数据源、BOM、筛选一致、10000 行上限）。
- Controller：`/reports`、`/reports/export`。
- 验收：JSON 与 CSV 指标/明细一致；文件名与 Content-Type 正确。

## T12 后端测试
- 新增 `AnalyticsQueryTest`、`SlaAnalyticsServiceTest`、`AiAnalyticsServiceTest`、`DispatchAnalyticsServiceTest`、`AgentAnalyticsServiceTest`、`ReportServiceTest`、`AnalyticsControllerTest`。
- 运行 `.\mvnw.cmd -B package`；全部通过。
- 验收：覆盖日期边界、空数据、权限 403、取消状态、SLA 分子分母、采纳率口径、导出内容。

## T13 前端 API 与筛选组件
- `src/api/analytics.ts`：类型 + 13 个调用函数。
- `src/api/request.ts`：`downloadFile()`。
- `src/composables/useAnalyticsRange.ts` + `src/components/DateRangeFilter.vue`。
- 验收：类型检查通过；下载错误能展示后端 message。

## T14 前端六页面
- `TicketOperationsView`、`SlaEfficiencyView`、`AgentPerformanceView`、`DispatchAnalyticsView`、`AiAnalyticsView`、`ReportCenterView`。
- 每页：页头、筛选、指标卡、差异化图表、明细表、加载/空/错误三态、口径说明。
- 验收：不复用驾驶舱结构；图表类型符合 spec 第 6 节；排名可排序。

## T15 菜单与路由
- `src/constants/menu.ts` 数据分析组 6 项；`src/router/index.ts` 6 条真实路由。
- 验收：菜单点击均进入真实页面，无 ComingSoon；刷新后路由正常。

## T16 类型检查与构建
- `node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit`
- `node .\node_modules\vite\bin\vite.js build`
- 验收：均退出码 0。

## T17 前后端联调与数据核对
- 重启后端/前端；逐接口 curl 验证（含 400/403/空数据）。
- 页面逐页验证筛选、图表、表格、导出；未来日期空状态。
- 用 SQL 对照 overview、SLA 分子分母、AI 采纳率。
- 验收：页面数字与接口一致；导出内容与筛选一致。

## T18 测试报告
- 产出 `docs/phase6-test-report.md`：环境、用例、结果、SQL 核对、性能 EXPLAIN、未测试项与原因。
- 验收：明确区分“已通过/未通过/未测试”；不伪造结果。

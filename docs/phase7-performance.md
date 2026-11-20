# 第七阶段：性能优化

> 原则：只做有真实收益、可验证的优化；不为了展示技术引入 Redis、消息队列或缓存层。
> 指标来自本机实测（前端 `vite build` 输出）；未执行的压测明确标注「未测试」。

## 1. 前端：路由懒加载与分包

### 1.1 问题

- 优化前 [router/index.ts](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/src/router/index.ts) 27 条路由全部同步 `import`，所有页面与 ECharts 打进同一个入口。
- 实测构建产物：单 JS **895 KB** + CSS 129 KB，登录页也要下载全部业务页面与图表库。

### 1.2 改动

| 改动 | 位置 |
| --- | --- |
| 除 `LoginView`、`AppLayout` 外，所有页面改为 `() => import(...)` 按路由懒加载 | `src/router/index.ts` |
| `manualChunks` 把 `vue/vue-router/@vue` 与 `echarts/zrender` 拆为独立 chunk | [vite.config.ts](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/vite.config.ts#L22-L38) |

### 1.3 优化前后对比（同机实测）

| 产物 | 优化前 | 优化后 |
| --- | --- | --- |
| 入口 JS | 895 KB（约） | `index` 36.09 KB（gzip 13.91 KB） |
| 框架 | 含在入口 | `vue` 96.17 KB（gzip 37.41 KB） |
| 图表库 | 含在入口 | `echarts` 555.70 KB（gzip 190.12 KB），仅图表页按需加载 |
| 业务页面 | 含在入口 | 独立 chunk，最大 `AgentManagementView` 16.46 KB，多数 < 15 KB |
| CSS | 129 KB | 仍为单一 CSS（Vite 对 CSS 的拆分收益有限，未强制拆分） |

结论：首屏不再需要下载图表库与全部业务页面；登录页与无图表页面只加载 `index + vue`（约 132 KB，gzip 约 51 KB），图表页再异步加载 `echarts`，并因文件名带 hash 可长期缓存。

## 2. 后端：慢 SQL 观测

### 2.1 实现

[SlowSqlLogger](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/java/com/serviceflow/ai/common/observability/SlowSqlLogger.java) 用 AOP 包住 `JdbcTemplate` 公开方法：

```text
slow_sql costMs=812 method=query sql=SELECT ... FROM tickets WHERE ...
```

- 阈值默认 500ms，可用 `SLOW_SQL_THRESHOLD_MS` 调整（`slow-sql.threshold-ms`）。
- 只记录 SQL 文本，不记录绑定参数；SQL 压缩空白并截断 300 字符，方便直接 `EXPLAIN`。
- 不影响原有查询结果与事务；未引入任何新数据访问框架。

### 2.2 验证

| 项 | 结果 |
| --- | --- |
| 编译与 172 个后端测试 | 通过 |
| 真实慢查询触发 | 未测试：当前 21 张工单，正常查询耗时远低于 500ms，需构造大数据量或用 `SLOW_SQL_THRESHOLD_MS=1` 观察，列入全量回归的可选步骤 |

## 3. 数据库：索引与查询

### 3.1 现状（复用第六阶段真实 EXPLAIN）

| 查询 | 执行计划 | 结论 |
| --- | --- | --- |
| 工单列表分页（status/priority/assignee 过滤） | 命中 `idx_tickets_*` 组合索引 | 正常 |
| SLA 聚合（按优先级统计超时率） | `ALL`（全表） | 21 行数据下全表扫描成本最低；数据量增长后需复查 |
| 人员负载聚合 | 命中 agents 主键与 tickets 索引 | 正常 |
| AI 效果聚合 | 走 `ai_analyses` 时间索引 | 正常 |

来源：`docs/phase6-test-report.md` 第 5 节。

### 3.2 本次结论

- **不新增索引**：当前数据量与执行计划未显示索引缺失导致的慢查询；新增索引会增加写入成本且缺少数据支撑。
- 观测手段已补齐（慢 SQL 日志），后续按真实 `slow_sql` 记录再做 `EXPLAIN` 与索引决策。
- 未发现 N+1：仓储层均以单条聚合 SQL 或 `IN` 批量查询完成，详情页按需分接口加载。

## 4. Java 层

| 检查项 | 结论 |
| --- | --- |
| 重复数据库查询 | 未发现同一请求内重复查询同一记录的明显问题；`TicketService` 详情一次性加载工单+事件+建议 |
| 线程池 | Tomcat 默认 200 线程；AI 分析为同步阻塞调用（最长 90s），高并发时可能占用线程，当前单实例、按需点击，量级可控 |
| 外部调用超时 | 连接 3s、读取 90s，均外置；Python 侧模型调用 60s，内部对超时/429/5xx 做 3 次退避重试 |
| 超时与重试 | Java 不重试 AI 请求（避免重复计费），由 Python 内部重试；Java 侧有 60 秒去重与每用户 10 次/分钟限流 |
| 事务范围 | 状态流转与调度写操作均在单事务内完成，未包含 AI 调用等外部耗时 |
| 不必要的同步 | 未使用 `synchronized` 热点；并发分配依赖数据库条件更新而非进程锁 |

## 5. Python 层

| 检查项 | 现状 | 结论 |
| --- | --- | --- |
| DeepSeek 超时 | `DEEPSEEK_TIMEOUT_SECONDS=60`（httpx） | 合理；与 Java 90s 读超时形成「内层先超时」 |
| 并发限制 | `asyncio` + 进程内 RateLimiter（12 次/分钟） | 单进程够用；多副本时需网关限流 |
| 参数校验 | Pydantic 严格 Schema + `extra="forbid"` | 已有 |
| 模型调用重复 | Java 侧 60 秒去重 + 前端按钮防重复点击 | 已有；未新增缓存 |
| 错误重试 | 仅超时/429/5xx 重试，4xx 不重试 | 避免无效计费 |
| 新增观测 | `model_call costMs`、`ai_request traceId` | 本次补充，便于定位模型耗时 |

## 6. 缓存评估（是否引入 Redis）

**结论：不引入。**

| 维度 | 评估 |
| --- | --- |
| 候选数据 | 工单列表、人员负载、分析聚合、AI 结果 |
| 命中率预期 | 工单与负载随操作实时变化，单用户重复查询占比低；AI 已有 60 秒去重 |
| 一致性成本 | 工单状态流转、调度、SLA 均需强一致，引入缓存必须做失效与双写，收益不足以抵消复杂度 |
| 数据规模 | 21 张工单、5 个用户，MySQL 单表查询 < 10ms |
| 触发条件 | 当 `slow_sql` 持续出现或 P95 > 500ms 且确认是重复读时，再评估 Redis，并明确 key、TTL、失效事件 |

## 7. 未测试项

- 未做真实压测（无 P95/P99、QPS 数据）。
- 未在 10 万级工单数据下重跑 EXPLAIN。
- 未测量首屏 LCP/FCP，只比较构建体积。
- 慢 SQL 日志的真实触发未构造慢查询验证（代码路径已在 172 个测试的编译与执行中加载）。

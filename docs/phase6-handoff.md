# 第六阶段收尾与续接说明（Handoff）

> 编写时间：2026-11-16 晚。本文件用于关机后次日直接续接，不依赖对话上下文。

## 1. 当前状态：第六阶段已交付

- 任务 T1–T18 全部完成，勾选记录见 `.trae/specs/phase6-analytics-center/tasks.md` 与 `checklist.md`。
- 后端 13 个分析接口可用，自动化测试 153 个全部通过；前端 6 个分析页浏览器验收通过；类型检查与生产构建通过。
- 数据库仅新增 5 个索引（迁移已执行，幂等），未改动任何既有表结构与数据。
- 所有项目服务已停止：8080（Java）、5173（Vite）、8000（FastAPI）当前均无监听。MySQL 本地服务未改动。

## 2. 交付物索引

文档（均在 `docs/`）：

| 文件 | 内容 |
| --- | --- |
| `phase6-audit.md` | 表/索引/枚举/SLA 逻辑/数据缺口/性能风险 |
| `phase6-metrics-design.md` | 每个指标的分子分母、来源、范围、特殊情况 |
| `phase6-api-design.md` | 13 个接口契约 |
| `phase6-test-report.md` | 用例、SQL 核对、EXPLAIN、浏览器验收、未测试项 |

代码：

- 后端：`backend-java/src/main/java/com/serviceflow/ai/analytics/{controller,dto,repository,service}`
- 后端测试：`backend-java/src/test/java/com/serviceflow/ai/analytics/`
- 迁移：`deploy/migrations/20261116-phase6-analytics-indexes.sql`
- 前端页面：`frontend/src/views/analytics/*.vue`（6 个）
- 前端基础设施：`frontend/src/api/analytics.ts`、`frontend/src/api/request.ts`（新增 `downloadFile`）、`frontend/src/composables/useAnalyticsRange.ts`、`frontend/src/components/DateRangeFilter.vue`
- 菜单/路由：`frontend/src/constants/menu.ts`、`frontend/src/router/index.ts`

## 3. 次日启动步骤（PowerShell，逐条执行）

前提：MySQL 8.4 服务已启动，库 `serviceflow` 可连接。

**① 后端（8080）**

```powershell
cd C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai\backend-java
$env:JAVA_HOME = 'D:\JDK21'
$env:MYSQL_PASSWORD = '<你的数据库密码>'
$env:JWT_SECRET = '<至少 32 字节的随机字符串>'
.\mvnw.cmd spring-boot:run
```

如需最新 jar：`.\mvnw.cmd -B package` 后 `java -jar target\serviceflow-backend-0.1.0.jar`（需先停止占用该 jar 的进程）。

**② AI 服务（8000，可选；仅 AI 相关页面需要）**

```powershell
cd C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai\ai-python
$env:DEEPSEEK_API_KEY = '<你的 API Key>'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

**③ 前端（5173）**

```powershell
cd C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai\frontend
node .\node_modules\vite\bin\vite.js
```

> 本机 npm 命令环境异常，前端一律用 `node .\node_modules\...` 直接执行；类型检查 `node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit`，构建 `node .\node_modules\vite\bin\vite.js build`。

**④ 验证入口**

- 前端：http://127.0.0.1:5173
- 分析页：`/#/analytics/tickets`、`/#/analytics/sla`、`/#/analytics/agents`、`/#/analytics/dispatch`、`/#/analytics/ai`、`/#/analytics/reports`
- 登录账号与阶段五相同（见 `docs/phase5-test-report.md`）。

## 4. 续接时的注意事项

1. `JWT_SECRET` 每次变更都会让已有登录态失效，重新登录即可，不影响数据。
2. 索引迁移已应用；如换库需重新执行 `deploy/migrations/20261116-phase6-analytics-indexes.sql`（可重复执行）。
3. 当前数据量很小（tickets 21、dispatch_logs 4、ai_analyses 9、ai_suggestions 4），页面出现空状态/样本不足提示属正常。
4. 历史 `dispatch_logs` 的 `RECOMMEND.agent_id` 为 NULL，因此调度采纳率为 `-`（无法判定），这是数据缺失而非缺陷，详见测试报告第 8 节。
5. 人员绩效综合分：区间内无分配工单记 0 分，避免「空闲得高分」。

## 5. 可选的后续事项（非阻塞）

- 构造较大数据量（如 10 万工单）验证统计 SQL 与索引收益。
- 有真实采纳样本后复核调度采纳率与实际效果。
- 用 USER 账号做一次页面级 403 验收。
- 在 Excel 中实际打开导出的 CSV，确认中文与列宽表现。
- 若后续需要「调度前后负载变化」，需新增调度时点的负载快照字段（本阶段明确不虚构）。

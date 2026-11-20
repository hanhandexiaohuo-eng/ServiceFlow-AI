# 第七阶段：系统日志与可观测性

> 改造原则：只增加请求追踪、访问日志与就绪检查，不引入 Prometheus/Grafana 等当前规模用不上的组件。
> 所有字段与行为均可在代码中定位；验证结果见文末。

## 1. 日志规范

### 1.1 统一格式（Java）

```text
2026-11-17 19:40:12.381 INFO  [7f3c1a2e-...] [admin] c.s.a.ticket.service.TicketService - access method=GET path=/api/tickets status=200 costMs=18 requestId=7f3c1a2e-... user=admin
```

配置位置：[application.yml](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/resources/application.yml#L24-L34)

| 字段 | 来源 |
| --- | --- |
| 时间 | `%d{yyyy-MM-dd HH:mm:ss.SSS}` |
| 级别 | `%-5level` |
| `[requestId]` | MDC，由 `RequestIdFilter` 写入 |
| `[user]` | MDC，认证成功后由 `AuthInterceptor` 写入用户名（不含 Token） |
| logger / message | 业务日志 |

### 1.2 请求 ID 规则

- 入口：[RequestIdFilter](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/java/com/serviceflow/ai/common/web/RequestIdFilter.java)，最高优先级执行。
- 读取上游 `X-Request-Id`：仅接受 `^[A-Za-z0-9._-]{8,64}$`，非法值（含空格、尖括号等）一律丢弃并重新生成 UUID，避免日志注入。
- 响应头回写 `X-Request-Id`，前端/网关可据此定位单次请求。
- 请求结束（无论成功/异常）在 `finally` 中输出一条访问日志，并清理 MDC，防止线程复用串号。

### 1.3 访问日志

单行记录 `method / path / status / costMs / requestId / user`：

- 不记录查询串，避免把 Token、手机号等带进日志。
- 不记录请求体与响应体。
- 异常请求同样会有一条 `status=500` 的访问日志，随后是 `GlobalExceptionHandler` 的堆栈日志，两者通过同一个 `requestId` 关联。

### 1.4 跨服务串联（Java → Python）

- [AiServiceClient](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/java/com/serviceflow/ai/intelligence/client/AiServiceClient.java#L47-L62) 把当前 MDC 中的 `requestId` 放进 `X-Request-Id` 请求头。
- nginx 反代时也会生成并透传 `X-Request-Id`（[nginx.conf](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/nginx.conf)），形成 `浏览器 → nginx → Java → Python` 的完整链路。
- Python 日志新增 `traceId`：优先使用请求头，业务侧 `requestId`（如 `analysis-12`）保留用于关联数据库记录。

```text
ai_request type=TICKET_ANALYSIS requestId=analysis-12 traceId=7f3c1a2e-...
model_call requestId=analysis-12 attempt=1 costMs=2310 status=SUCCESS
ai_success type=TICKET_ANALYSIS requestId=analysis-12 latencyMs=2450
```

### 1.5 敏感信息约束

| 禁止进入日志 | 现状 |
| --- | --- |
| `Authorization` / JWT | 不打印请求头，只打印用户名 |
| 数据库密码、连接串密码 | 驱动与 Hikari 均不打印密码；业务代码未输出数据源配置 |
| DeepSeek API Key | 只在 Python 请求头中使用，日志仅记录状态码与错误类型 |
| 工单正文 / Prompt 全文 | AI 客户端只记录 `path/costMs/status/code` |
| 完整用户隐私 | 只记录用户名，不记录手机号/邮箱 |

## 2. 审计记录（已有能力，复用）

| 审计对象 | 存储 | 说明 |
| --- | --- | --- |
| 工单状态流转 | `ticket_events` | 动作、前后状态、操作人、备注、时间，由 `TicketWorkflowEngine` 写入 |
| 调度操作 | `dispatch_logs` | 推荐/分配/转派动作与操作人 |
| AI 调用 | `ai_analyses` | 分析类型、状态、耗时、模型、Prompt 版本、错误码 |
| AI 建议采纳 | `ai_suggestions` | 建议状态与采纳人 |

这些表是「关键业务操作审计」的真实实现，本次不新增重复的审计表。

## 3. 健康检查

| 端点 | 语义 | 依赖 |
| --- | --- | --- |
| `GET /api/health` | 存活探针：进程可响应即 UP | 无 |
| `GET /api/health/ready` | 就绪探针：返回各组件真实状态 | MySQL（`SELECT 1`）+ Python AI（`GET /health`，1 秒超时） |

状态语义：

- `UP`：全部组件正常，HTTP 200。
- `DEGRADED`：MySQL 正常、AI 不可用；HTTP 200（核心业务可用，AI 功能降级），`checks` 中 `ai=DOWN`。
- `DOWN`：MySQL 不可用；HTTP 503。

AI 检查使用独立的 1 秒超时客户端（[AiHealthChecker](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/java/com/serviceflow/ai/health/service/AiHealthChecker.java)），不会被 90 秒模型读超时拖住。`ai.service.enabled=false`（如 test profile）时 AI 组件直接视为 UP 并注明已关闭。

## 4. 排查手册

| 现象 | 查询方式 |
| --- | --- |
| 用户反馈某次请求失败 | 从前端响应头或浏览器网络面板拿到 `X-Request-Id`，在 Java 日志与 Python 日志中同时搜索 |
| AI 分析超时 | 搜 `ai_call status=NETWORK_ERROR` 或 `ai_call_timeout`，结合 `costMs` 判断是连接还是读超时 |
| 接口变慢 | 搜 `access` 行按 `costMs` 排序，先定位 Controller，再看耗时是否在 SQL 或 AI 调用 |
| 数据库不可用 | `curl /api/health/ready` 看 `mysql` 组件的 message，日志中搜 `mysql` 与 `HikariPool` |
| 登录异常 | 日志中只有用户名，没有 Token；结合 401 访问日志与 `AuthInterceptor` 抛出的业务文案判断 |

## 5. 验证记录

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 请求 ID 生成、透传、非法值兜底、MDC 清理 | `RequestIdFilterTest`（3 个用例） | 见 `docs/phase7-test-report.md` |
| Java 全量单元测试 | `mvnw -B test` | 见测试报告 |
| Python 测试 | `pytest -q` | 见测试报告 |
| 真实链路（浏览器 → Java → Python） | 需要启动三端服务并配置 Key | 未在本步骤执行，列入全量回归 |

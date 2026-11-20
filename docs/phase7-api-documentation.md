# 第七阶段：接口文档与统一规范

> OpenAPI 由 springdoc-openapi 2.8.9 生成（已验证与 Spring Boot 3.5.16 编译通过）。
> 文档地址在运行中的后端服务上可访问；本机未启动服务时请用 `mvnw spring-boot:run` 启动后查看。

## 1. 文档地址

| 内容 | 地址 |
| --- | --- |
| Swagger UI | `http://127.0.0.1:8080/swagger-ui/index.html` |
| OpenAPI JSON | `http://127.0.0.1:8080/v3/api-docs` |
| 容器部署后（经 nginx） | `http://127.0.0.1:8085/swagger-ui/index.html` 不可用（nginx 只反代 `/api`）；需直连后端容器或临时映射端口 |
| Python AI 文档 | `http://127.0.0.1:8000/docs`（FastAPI 自带 Swagger UI） |
| Python OpenAPI | `http://127.0.0.1:8000/openapi.json` |

开关：`SPRINGDOC_ENABLED=false` 关闭 JSON，`SPRINGDOC_UI_ENABLED=false` 关闭 Swagger UI。

## 2. 统一返回结构

```json
{ "code": 200, "message": "OK", "data": {}, "timestamp": "2026-11-17 19:40:12" }
```

| 字段 | 说明 |
| --- | --- |
| `code` | 业务码，与 HTTP 状态保持一致 |
| `message` | 可读文案；校验失败时为字段错误汇总 |
| `data` | 业务数据；删除/无返回时为 `null` |
| `timestamp` | 服务端时间 |

## 3. 统一错误码

| HTTP / code | 场景 | 示例文案 |
| --- | --- | --- |
| 400 | 参数校验失败、枚举非法、类型转换失败 | `标题不能为空`、`参数格式不正确: assigneeId` |
| 401 | 未登录、Token 失效、账密错误 | `登录已失效，请重新登录` |
| 403 | 角色或数据归属不足 | `无权操作该工单` |
| 404 | 资源不存在 | `工单不存在` |
| 409 | 并发状态冲突 | `工单状态已变更，请刷新后重试` |
| 500 | 未预期异常 | `服务器内部错误`（堆栈只进服务端日志） |
| 502/503/504 | AI 服务不可用/超时 | 保留 Python 服务状态码，并通过响应头 `X-AI-Error-Code` 给出错误码 |

AI 错误码（响应头）：`AI_TIMEOUT`、`AI_NETWORK_ERROR`、`AI_INVALID_OUTPUT`、`AI_CONFIG_MISSING`、`AI_RATE_LIMITED` 等。

## 4. 鉴权与权限

- 登录：`POST /api/auth/login`，请求体 `{ "username": "...", "password": "..." }`，返回 Token 与用户信息。
- 后续请求：`Authorization: Bearer <token>`；Swagger UI 右上角 Authorize 填入 Token 即可调试。
- 公开接口：`/api/auth/login`、`/api/health/**`、`/v3/api-docs/**`、`/swagger-ui/**`。
- 角色规则（接口与服务层双重校验）：

| 角色 | 能力 |
| --- | --- |
| ADMIN | 全部接口：用户、人员、调度、分析、知识库维护、报表 |
| AGENT | 工单处理与流转、查看调度与负载、AI 分析与采纳、知识库检索 |
| USER | 创建工单、查看/取消自己的工单、查看自己的 AI 分析结果 |

## 5. 分页与时间规范

- 分页参数：`page` 从 1 开始；`pageSize` 默认 10，上限 100（超出由服务端校正）。
- 分页返回：`{ "items": [], "total": 0, "page": 1, "pageSize": 10 }`。
- 时间：`yyyy-MM-dd HH:mm:ss`，时区 `Asia/Shanghai`；查询区间参数为 `startDate` / `endDate`（日期或日期时间）。
- 排序：列表默认按创建时间倒序，不允许前端拼接排序字段，避免 SQL 注入。

## 6. 接口索引（按 Swagger 分组）

### 6.1 认证与用户（01/02）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，公开 |
| GET | `/api/auth/me` | 当前用户信息 |
| GET | `/api/users` | 用户列表（ADMIN） |

### 6.2 工单与流转（03）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tickets` | 创建工单 |
| GET | `/api/tickets` | 分页查询（状态/优先级/负责人/关键词） |
| GET | `/api/tickets/{id}` | 详情（含 SLA） |
| PUT | `/api/tickets/{id}` | 编辑（创建人或 ADMIN） |
| DELETE | `/api/tickets/{id}` | 删除（ADMIN） |
| GET | `/api/tickets/{id}/events` | 流转事件 |
| POST | `/api/tickets/{id}/assign` | 分配 |
| POST | `/api/tickets/{id}/transfer` | 转派 |
| POST | `/api/tickets/{id}/accept` | 接单 |
| POST | `/api/tickets/{id}/start` | 开始处理 |
| POST | `/api/tickets/{id}/resolve` | 解决 |
| POST | `/api/tickets/{id}/close` | 关闭 |
| POST | `/api/tickets/{id}/cancel` | 取消 |

### 6.3 SLA（04）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/sla/policies` | 各优先级时限与预警阈值（来自配置） |

### 6.4 人员、技能与调度（05/06/07）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/skills` / POST | 技能列表 / 新增（ADMIN） |
| GET | `/api/agents` | 人员列表 |
| GET | `/api/agents/workload` | 全部人员负载 |
| GET | `/api/agents/workload/overview` | 负载概览 |
| GET | `/api/agents/{id}` | 人员详情 |
| POST / PUT | `/api/agents` / `/api/agents/{id}` | 新增 / 编辑（ADMIN） |
| GET / PUT | `/api/agents/{id}/skills` | 技能查询 / 设置 |
| PUT | `/api/agents/{id}/availability` | 可用性 |
| GET | `/api/agents/{id}/workload` | 个人负载 |
| GET | `/api/dispatch/tickets` | 待调度工单 |
| GET | `/api/dispatch/recommendations/{ticketId}` | 规则推荐 Top5 |
| POST | `/api/dispatch/assign` | 确认分配（带并发保护） |
| GET | `/api/dispatch/history` | 调度历史 |

### 6.5 AI 分析与会话（08/09/10）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ai/analyses` | 发起分析（类型：分诊/优先级/方案/知识/调度/问答） |
| GET | `/api/ai/analyses` | 分析历史（分页） |
| GET | `/api/ai/analyses/{id}` | 分析详情 |
| POST | `/api/ai/suggestions/{id}/accept` | 采纳建议（写回工单） |
| POST | `/api/ai/suggestions/{id}/reject` | 驳回建议 |
| GET | `/api/ai/suggestions/{id}` | 建议详情 |
| POST / GET | `/api/ai/sessions` | 创建 / 列出会话 |
| GET / PUT / DELETE | `/api/ai/sessions/{id}` | 会话详情 / 改名 / 删除 |
| POST | `/api/ai/sessions/{id}/messages` | 发送消息（多轮） |
| GET | `/api/ai/tickets/{ticketId}/context` | AI 上下文 |

### 6.6 知识库（11）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/knowledge` | 检索（关键词/分类） |
| GET | `/api/knowledge/categories` | 分类 |
| GET | `/api/knowledge/{id}` | 详情 |
| POST / PUT | `/api/knowledge` / `/api/knowledge/{id}` | 新增 / 编辑（ADMIN 或维护角色） |
| GET / POST | `/api/knowledge/{id}/tickets` | 关联工单查询 / 建立关联 |
| DELETE | `/api/knowledge/{id}/tickets/{ticketId}` | 解除关联 |
| GET | `/api/knowledge/tickets/{ticketId}` | 按工单查知识 |

### 6.7 分析与报表（12~16）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/analytics/overview` | 驾驶舱总览 |
| GET | `/api/analytics/tickets/trend` | 工单趋势 |
| GET | `/api/analytics/tickets/distribution` | 工单分布 |
| GET | `/api/analytics/sla/overview` / `sla/trend` | SLA 概览 / 趋势 |
| GET | `/api/analytics/agents/performance` / `ranking` / `trend` | 人员绩效 |
| GET | `/api/analytics/dispatch/overview` | 调度分析 |
| GET | `/api/analytics/ai/overview` / `ai/suggestions` | AI 效果 |
| GET | `/api/analytics/reports` | 综合报表 |
| GET | `/api/analytics/reports/export` | 报表导出（CSV） |

### 6.8 健康检查（17）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 存活 |
| GET | `/api/health/ready` | 就绪（MySQL + AI） |

## 7. Python AI 服务接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 进程存活 |
| POST | `/ai/ticket-analysis` | 工单分诊分析 |
| POST | `/ai/priority-recommendation` | 优先级建议 |
| POST | `/ai/solution-generation` | 处理方案 |
| POST | `/ai/knowledge-recommendation` | 知识推荐 |
| POST | `/ai/dispatch-assistance` | 调度辅助 |
| POST | `/ai/ticket-qa` | 工单问答 |

统一请求体：`{ "requestId": "...", "payload": { ... } }`；统一响应：`{ "requestId", "type", "result", "model", "promptVersion", "latencyMs" }`。所有接口要求 JSON、拒绝未知字段，错误以 `{ "code", "message", "requestId" }` 返回。

## 8. 验证记录

| 项 | 结果 |
| --- | --- |
| springdoc 2.8.9 与 Boot 3.5.16 兼容 | 编译通过（`mvnw -DskipTests compile`） |
| 17 个 Controller 均已分组 | 已逐文件确认 `@Tag` |
| Swagger UI 实际渲染、`/v3/api-docs` 返回 | 未测试：需重启后端服务后浏览器访问，列入全量回归 |
| Python `/docs` 可访问 | 未测试：需启动 uvicorn |

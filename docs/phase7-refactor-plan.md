# 第七阶段工程化改造方案

> 依据 `docs/phase7-audit.md` 的实测结论。原则：只补工程能力，不改业务语义；每步可验证后再进入下一步。
> 不重写项目，不动数据库历史数据，不引入无场景的技术。

## 1. 目标与边界

**目标**：交付一个可构建、可容器化部署、配置外置、日志可追踪、接口有文档、测试可自动执行、文档可交接的模块化单体。

**不做**：

- 不引入 Redis / 消息队列 / 微服务拆分（当前 21 张工单，无依据）。
- 不替换 JdbcTemplate，不改状态机、SLA 公式、分析口径。
- 不伪造压测数据，不在 CI 中调用真实 DeepSeek。
- 不为凑技术点增加权限模型、审计表或缓存层。

## 2. 改造清单（按实施顺序）

### 步骤 1：配置管理与安全加固

| 改动 | 内容 | 验收 |
| --- | --- | --- |
| 后端 profile | 新增 `application-dev.yml`、`application-test.yml`、`application-prod.yml`；公共项留在 `application.yml` | `dev` 与 `prod` 均可启动；敏感项仍走环境变量 |
| SLA 外置 | 新增 `sla.policy.<priority>.hours`、`sla.warning-minutes`，`TicketSlaService` 读取配置；默认值保持 4/8/24/72、120 | 现有 SLA 测试全绿；改配置后接口返回同步变化 |
| 前端环境化 | 新增 `.env.development` / `.env.production`；`vite.config.ts` 代理目标改 `loadEnv`；API 基址支持 `VITE_API_BASE_URL` | 本地 dev 正常；build 产物不含本地地址 |
| 密钥卫生 | 根级 `.env.example`（仅占位符）；`.gitignore` 增加 `*.log.*`、`backend-java/logs/` | `git status` 无日志文件；`git ls-files` 无 `.env` |
| 安全核查 | SQL 注入、越权、上传、CORS、异常泄露逐项复核 | 输出 `docs/phase7-security.md`，含凭证轮换提醒（不复述原值） |

### 步骤 2：Docker 容器化

| 改动 | 内容 |
| --- | --- |
| `backend-java/Dockerfile` | 多阶段：Maven Wrapper 构建 → JRE 17 运行；非 root；`JAVA_OPTS` 可配 |
| `ai-python/Dockerfile` | 基于 `python:3.11-slim`，仅装 runtime 依赖；uvicorn 单进程 |
| `frontend/Dockerfile` + `nginx.conf` | Node 构建 → nginx 托管静态文件，`/api` 反代 `backend:8080` |
| `docker-compose.yml`（根级或 deploy 扩展） | mysql + backend + ai + frontend；内部网络；卷 `mysql-data`；全部 healthcheck |
| 启动顺序 | backend 依赖 mysql 健康 + ai 健康；frontend 依赖 backend 健康；不使用裸 `depends_on` |
| 迁移 | MySQL 首启执行 `init/`；增量迁移提供独立脚本与文档说明，不自动跑全量 migrations |
| 文档 | `docs/phase7-deployment.md`：启动、停止、重启、日志、备份、常见故障 |

本机若无 Docker，只交付文件与验证步骤，不宣称「已部署成功」。

### 步骤 3：日志与可观测性

| 改动 | 内容 |
| --- | --- |
| 请求 ID | 新增 `RequestIdFilter`：读 `X-Request-Id` 或生成 UUID，写入 MDC 与响应头 |
| 访问日志 | 每个请求记录 `method/path/status/costMs/requestId/user`，不记正文与 Token |
| 跨服务串联 | Java 调 Python 时把 requestId 放进请求体与 `X-Request-Id`；Python 日志输出同一 ID |
| 健康检查 | `/api/health/ready` 增加 `ai` 组件检查（超时 1s，失败降级为 DOWN 原因），保留 `mysql` |
| 日志脱敏 | 统一 pattern；禁止打印 Authorization、密码、完整 URL 凭据 |
| 文档 | `docs/phase7-observability.md`：日志字段、ID 规则、健康检查语义、排查手册 |

### 步骤 4：性能优化

| 改动 | 验收 |
| --- | --- |
| 前端路由懒加载 + `manualChunks`（vue/echarts 分包） | 首屏 JS 明显小于 895 KB；构建通过 |
| 后端慢 SQL 日志（>500ms） | 触发分析查询时能看到耗时；不改变结果 |
| 关键查询 EXPLAIN 前后对比 | 记录在 `docs/phase7-performance.md`；无索引依据不新增索引 |
| AI 调用并发/超时审视 | 记录现状与建议；不盲目加重试与缓存 |
| 缓存评估 | 明确结论「是否需要 Redis」，需要则写数据、TTL、失效条件；当前预期为不需要 |

### 步骤 5：接口文档与统一规范

| 改动 | 内容 |
| --- | --- |
| springdoc | 引入与 Boot 3.5.16 兼容版本，先构建验证再提交；仅暴露必要分组 |
| 注解 | 为 17 个 Controller 补 `@Tag/@Operation`，统一错误码表与分页/时间格式说明 |
| 权限说明 | 每个接口标注所需角色（ADMIN/AGENT/USER） |
| Python | FastAPI 默认 OpenAPI 保留，补 summary/description |
| 文档 | `docs/phase7-api-documentation.md`：地址、鉴权方式、错误码、12 类接口索引 |

### 步骤 6：自动化测试与 CI

| 改动 | 内容 |
| --- | --- |
| Java | `mvnw clean package`；新增权限测试补齐 `knowledge`、`UserQueryService`；不依赖真实 MySQL |
| Python | `pytest`，模型调用全 Mock；补参数校验与异常用例 |
| 前端 | `vue-tsc --noEmit` + `vite build`；补关键页面冒烟（先不引入重量级 E2E 框架） |
| CI | `.github/workflows/ci.yml`：JDK 21、Python 3.11、Node 20；四步串行；不注入任何真实密钥 |
| 文档 | `docs/phase7-ci.md`：触发条件、步骤、如何本地复现、失败排查 |

### 步骤 7：交付文档

- 重写 `README.md`（背景、功能、架构图、流程图、数据库、启动、Docker、环境变量、测试、接口地址、截图、目录、FAQ）。
- 新增 `docs/architecture.md`、`business-flow.md`、`database-design.md`、`deployment-guide.md`、`interview-guide.md`。
- `interview-guide.md` 的每个答案必须引用真实类/表，不写未实现的设计。

### 步骤 8：演示与回归

- `docs/demo-guide.md`：12 步演示脚本 + 异常场景 + 外部 Key 前置条件。
- `docs/phase7-test-report.md`：已通过 / 未通过 / 未测试 / Mock / 真实环境分开记录。
- 回归基线：Java 153 个测试、Python 33 个测试、前端类型检查与构建、登录与核心流程、日志脱敏抽查。

## 3. 交付物索引

| 类型 | 文件 |
| --- | --- |
| 审计 | `docs/phase7-audit.md` |
| 方案 | `docs/phase7-refactor-plan.md`（本文件） |
| 安全 | `docs/phase7-security.md` |
| 部署 | `docs/phase7-deployment.md` + 3 个 Dockerfile + `docker-compose.yml` + `nginx.conf` |
| 可观测性 | `docs/phase7-observability.md` |
| 性能 | `docs/phase7-performance.md` |
| 接口 | `docs/phase7-api-documentation.md` + OpenAPI |
| CI | `.github/workflows/ci.yml` + `docs/phase7-ci.md` |
| 交付文档 | `README.md`、`docs/architecture.md`、`business-flow.md`、`database-design.md`、`deployment-guide.md`、`interview-guide.md` |
| 演示/测试 | `docs/demo-guide.md`、`docs/phase7-test-report.md` |

## 4. 风险控制

| 风险 | 控制 |
| --- | --- |
| springdoc 与 Boot 3.5 不兼容 | 先只加依赖跑 `mvnw package`，失败即换版本或降级为手写 OpenAPI，不阻塞其它模块 |
| Docker 本机不可用 | 只交付文件与验证命令，测试报告标注「未在容器中执行」 |
| 懒加载改动引入路由回归 | 构建 + 逐页浏览器冒烟 |
| 请求 ID 过滤器影响性能/日志量 | 只加一个 UUID 与 MDC，访问日志单行 |
| 配置外置漏项 | 用「本地不设 .env 也能以默认值启动」作为回归条件 |

## 5. 完成定义（DoD）

1. 每条改造都有对应真实验证记录，未执行项明确标注。
2. 不改坏既有业务：登录、工单全流程、SLA、调度、AI、知识库、分析六页全部可回归。
3. 仓库内无明文密钥；`git status` 干净可交付（用户确认后再提交）。
4. 新人按 README + deployment-guide 能从零启动。

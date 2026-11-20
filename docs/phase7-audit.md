# 第七阶段审计报告：企业级工程化与系统交付

> 审计时间：2026-11-17。审计范围：整个仓库（backend-java、frontend、ai-python、deploy、docs、Git）。
> 所有数字与结论来自当次真实执行；未运行过的项明确标注「未验证」。
> 本报告只诊断，不重构。改造方案见 `docs/phase7-refactor-plan.md`。

## 0. 系统规模基线

| 指标 | 实测值 |
| --- | --- |
| 后端主代码 | 174 个 Java 文件，17 个 Controller，70 个路由映射 |
| 后端测试 | 25 个测试文件，153 个 `@Test` |
| 前端 | 45 个 `.vue`、21 个 `.ts`，27 条路由 |
| 前端构建产物 | 单 JS chunk 895 KB + CSS 129 KB（`dist` 实测） |
| Python | 6 个 AI 接口，3 个测试文件、33 个测试函数 |
| 数据库 | 21 张工单、5 个用户，14 张业务表 |
| 容器化 | 无任何 Dockerfile / .dockerignore / CI 工作流 |

## 1. 十项重点检查结果

| # | 检查项 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 重复业务代码 | 存在问题 | 权限判断在 `TicketService`、`TicketWorkflowEngine`、`AiTicketAccessGuard`、`AnalyticsSecurity` 各写一份；时间格式化在多处重复 |
| 2 | 不合理跨层调用 | 基本通过 | Controller 未直接使用 Repository；未见 entity 直接返回（统一 DTO） |
| 3 | 过度复杂的 Service | 存在问题 | `AnalyticsRepository` 451 行、`AiAnalysisService` 422 行、`ReportService` 348 行、`DispatchService` 339 行、`AgentService` 322 行、`KnowledgeService` 317 行 |
| 4 | Controller 写业务逻辑 | 基本通过 | 仅做参数绑定与响应组装；状态机在 `TicketWorkflowService` |
| 5 | 硬编码配置 | 有问题 | SLA 时限 4/8/24/72 与 120 分钟预警写死在 `TicketSlaService.java:23-35`；前端代理地址写死在 `vite.config.ts:8-16` |
| 6 | 敏感信息泄露 | 有历史风险 | 仓库未跟踪 `.env`（`git ls-files` 仅 `.env.example`）；但历史对话中出现过 DeepSeek Key 与数据库密码，需轮换（本报告不复述） |
| 7 | 无用依赖 | 通过 | `pom.xml` 7 组直接依赖均有用途；`package.json` 仅 echarts/vue/vue-router，无冗余框架 |
| 8 | 缺失测试 | 部分缺失 | `knowledge` 包无任何测试；`UserQueryService`、`SlaController`、`ReportController` 无直接测试；前端与 Python 无 CI 串联 |
| 9 | 不合理数据库查询 | 潜在风险 | 全参数化未见注入；但 6 个分析 SQL 在数据增长后会全表聚合（第六阶段 EXPLAIN 已记录 ALL），缺慢 SQL 观测 |
| 10 | 前后端接口不一致 | 通过（抽样） | `src/api/*.ts` 的 `/api/**` 路径均能在 Controller 找到对应映射；60+ 函数未逐一回归，列入联调项 |

## 2. 已确认问题

### C1 三端均未容器化

只有 MySQL 的 Compose，没有应用镜像。

- [docker-compose.yml](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/deploy/docker-compose.yml) 仅定义 `mysql` 服务（第 3-31 行）。
- 全仓库无 Dockerfile、无 .dockerignore、无 nginx 配置。
- 影响：无法一条命令交付；环境依赖（JDK21、Node、Python）全靠本机。

### C2 配置未分环境

- `backend-java` 只有一个 [application.yml](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/resources/application.yml)，没有 `application-dev/test/prod.yml`。
- 数据库、JWT、AI 地址已用环境变量占位（第 9-11、49-54 行），这是优点；缺的是 profile 组织与统一示例。
- 前端 `import.meta.env` 使用数为 **0**，构建期无法切换 API 地址。

### C3 SLA 与阈值硬编码

[TicketSlaService.java](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/java/com/serviceflow/ai/ticket/service/TicketSlaService.java#L28-L35) 用 `switch` 返回 4/8/24/72 小时，第 23 行预警 120 分钟。

影响：调整 SLA 必须改代码重新打包，运维不可配。调度权重已配置化（`dispatch.*`），SLA 未对齐。

### C4 前端首屏与路由

- [router/index.ts](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/src/router/index.ts) 27 条路由全部同步 `component:` 引入，`import(` 懒加载数为 **0**。
- 构建产物为单个 895 KB JS，所有页面与 ECharts 打包在一起。
- 9 个页面超过 790 行，最大 `TicketDetailView.vue` 1158 行。

### C5 没有机器可读接口契约

- `pom.xml` 无 springdoc，仓库无 OpenAPI/Swagger 文件。
- 17 个 Controller、70 个映射只能靠读源码或第六阶段 Markdown 了解。

### C6 请求链路无法串联

- Java 侧无 MDC、无请求 ID 过滤器；`AiServiceClient` 日志只有 `path/costMs/status`（第 58-82 行），不含 requestId。
- Python 侧有 `requestId` 与 `latencyMs` 日志（[main.py](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/ai-python/app/main.py#L68-L84)），但 Java 未在日志中透传，跨服务无法按同一 ID 关联。
- 无接口耗时统一日志。

### C7 健康检查只覆盖一半

[HealthService.java](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/src/main/java/com/serviceflow/ai/health/service/HealthService.java#L29-L33) 的 `/api/health/ready` 只检查 MySQL，不检查 Python AI 服务；无 Actuator、无指标端点。

### C8 日志归档未被忽略

`.gitignore` 只有 `*.log`，而 `backend-java/logs/` 下有 `*.log.2026-11-14.0.gz` 归档，导致该目录在 `git status` 中作为未跟踪项出现。日志本身未被跟踪，风险是误提交。

### C9 前端缺工程化脚本

[package.json](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/package.json#L6-L10) 只有 `dev/build/preview`，无 lint、format、test；无 ESLint/Prettier 配置。

### C10 文档与实现脱节

[README.md](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/README.md#L5) 第 5 行仍写「第一阶段…尚未包含工作流、智能调度…」；而实际已完成 10 个业务模块。

菜单仍有 2 个占位项（[menu.ts](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/src/constants/menu.ts#L87) 用户管理、第 96 行操作日志），其中「操作日志」与可观测性/审计相关。

### C11 Git 交付状态

- 100+ 个第三至第六阶段文件未提交，工作区与 HEAD 差距很大。
- 审计过程中出现 `.git/index.lock` 残留（已回收），说明存在并发/异常退出的 Git 操作。
- 无 tag、无 CI、无发布流程。

## 3. 潜在风险（尚未构成故障，但需处理）

| 编号 | 风险 | 说明 |
| --- | --- | --- |
| R1 | Token 存 localStorage | [request.ts](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/src/api/request.ts#L23-L32) 读写 localStorage；无 `v-html`，XSS 面较小，但仍建议 CSP 与 HttpOnly 方案评估 |
| R2 | CORS 完全未配置 | `WebMvcConfig` 注释明确「不放行跨域」，同源代理下正确；容器化若前后端不同源会直接失败，需要可配置项而不是 `*` |
| R3 | AI 读超时 90 秒 | `application.yml:51`；Tomcat 默认 200 线程，高并发分析会占满线程池，缺隔离与限流指标 |
| R4 | Hikari 连接池仅 5 | `application.yml:14`；单实例够用，容器多副本时需外置 |
| R5 | 分析 SQL 全表聚合 | 第六阶段 EXPLAIN 已记录 SLA 聚合为 `ALL`；21 行数据下最优，数据增长后需观测 |
| R6 | 动态 SQL 拼接 | 分析仓库用文本块拼固定片段 + 绑定参数，当前无注入；后续新增筛选必须继续绑定参数 |
| R7 | 日志无脱敏器 | 现在不打印请求体；一旦开启 debug 访问日志，可能带出 Token/正文 |
| R8 | `initialization-fail-timeout=-1` | 有意允许 DB 未就绪时启动，但 Compose 必须靠 healthcheck 控制依赖，不能只靠 `depends_on` |

## 4. 建议改进（按改造顺序）

1. **配置与安全**：`application-{dev,test,prod}.yml`；SLA 时限与预警外置；前端 `.env.*` + `import.meta.env`；统一根级 `.env.example`；`.gitignore` 补 `*.log.*`、`logs/`；凭证轮换提醒。
2. **容器化**：三端 Dockerfile + nginx 静态站点与 `/api` 反代 + Compose 统一编排 + 全部 healthcheck + MySQL 持久卷 + 迁移可控执行。
3. **可观测性**：Java 请求 ID Filter（MDC，响应头 `X-Request-Id`）+ 访问耗时日志 + Python 透传同一 ID；`/api/health/ready` 增加 AI 服务检查；日志脱敏。
4. **性能**：前端路由懒加载 + 手动分包；后端慢 SQL 日志与关键查询 EXPLAIN 对比；AI 调用并发与超时评估（不盲目上 Redis）。
5. **接口文档**：引入与 Spring Boot 3.5 兼容的 springdoc，补权限说明与统一错误码表。
6. **测试与 CI**：Java `mvnw clean package`、Python `pytest`、前端 `vue-tsc --noEmit` + `vite build` 串成 GitHub Actions；外部 AI 用 Mock。
7. **文档与演示**：README 重写 + 5 份交付文档 + 演示脚本 + 全量回归报告。

## 5. 不建议修改的稳定模块

| 模块 | 理由 |
| --- | --- |
| 工单状态机 `TicketWorkflowEngine` + `ticket_events` | 六阶段已验证，动作与流转记录完整；重写只会引入回归 |
| `ApiResponse` + `GlobalExceptionHandler` | 结构统一、错误码与 HTTP 状态一致，已被 153 个测试覆盖 |
| JWT 实现 `JwtService` | 已有 32 字节 fail-fast 校验，密钥来自环境变量；无需替换为 Spring Security 全家桶 |
| `AnalyticsQuery` 校验 | 区间、跨度、枚举校验集中且已测试，是分析口径的正确性基础 |
| SLA 计算公式 | 只做「时限可配」，不改公式与已完成状态的判定口径 |
| JdbcTemplate 选型 | 项目 SQL 复杂且可控，引入 JPA/MyBatis 无收益 |
| 智能调度权重配置 | 已外置于 `dispatch.*`，无需重构 |
| 现有 153 个后端测试 | 作为回归基线保留，不做无意义改写 |

## 6. 未验证项（不写入结论）

- 未做真实压测，不掌握 P95/P99 与吞吐。
- 未对全部 70 个映射逐一做前后端字段比对（仅抽样）。
- 未在 Docker 环境运行过（当前无镜像）。
- 未验证 springdoc 与 Spring Boot 3.5.16 的实际兼容性，需在接口文档模块实测。
- 未统计测试覆盖率（无 JaCoCo）。

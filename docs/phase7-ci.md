# 第七阶段：自动化测试与 CI

> 工作流文件：[.github/workflows/ci.yml](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/.github/workflows/ci.yml)
> 本文档中的本地命令均已在本机执行过；GitHub Actions 的实际运行未验证（仓库尚未推送/无 Runner 记录），结果以 `docs/phase7-test-report.md` 为准。

## 1. 触发条件

| 事件 | 说明 |
| --- | --- |
| `push` 到 `main` / `master` | 主分支提交即构建 |
| `pull_request` | 合并前校验 |
| `workflow_dispatch` | 手动触发 |

同一分支的新构建会取消上一次未完成的运行（`concurrency.cancel-in-progress`）。

## 2. 三个并行任务

| Job | 运行环境 | 步骤 |
| --- | --- | --- |
| `backend` | ubuntu-latest + Temurin JDK 21 + Maven 缓存 | `chmod +x mvnw` → `./mvnw -B clean package`（编译 + 172 个单元测试 + 打 jar） |
| `python` | ubuntu-latest + Python 3.11 + pip 缓存 | `pip install -r requirements-dev.txt` → `pytest -q` |
| `frontend` | ubuntu-latest + Node 20 + npm 缓存 | `npm ci` → `npx vue-tsc --noEmit` → `npx vite build` |

### 2.1 为什么后端 CI 不需要 MySQL

全部后端测试为纯单元测试：Mockito mock 仓储、`MockMvcBuilders.standaloneSetup` 构造 Controller，没有 `@SpringBootTest` / `@JdbcTest`。因此 CI 不启动数据库，也不读取任何数据库密码。

### 2.2 为什么不在 CI 调用真实 DeepSeek

- 真实调用会产生费用、受网络与限流影响，且结果不确定，不适合作为每次提交的门禁。
- Python 测试通过 `monkeypatch` 替换模型调用，只验证 Prompt 组装、Schema 校验、错误映射与限流逻辑。
- CI 中仅注入一个占位环境变量 `DEEPSEEK_API_KEY=test-key-not-used`，确保配置读取路径不因缺变量崩溃，但不会发起真实请求。

### 2.3 密钥与密码

工作流文件不含任何真实 Key、密码或 JWT Secret；也没有引用仓库 Secrets。若未来需要集成测试环境，必须通过 GitHub Secrets 注入，且不得打印。

## 3. 本地复现（与 CI 等价）

```powershell
# Java
$env:JAVA_HOME='D:\JDK21'
cd backend-java
.\mvnw.cmd -B clean package

# Python
cd ai-python
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest -q

# 前端
cd frontend
node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit
node .\node_modules\vite\bin\vite.js build
```

## 4. 测试覆盖范围

| 层 | 已有覆盖 | 说明 |
| --- | --- | --- |
| 认证 | `AuthControllerTest`、`AuthInterceptorTest`、`JwtServiceTest`、`AuthServiceTest` | 登录、Token 校验、角色读取、401 |
| 工单 | `TicketControllerTest`、`TicketServiceTest`、`TicketSlaServiceTest`、`TicketWorkflowEngineTest` | 创建、查询、编辑权限、状态机流转、SLA |
| 调度 | `DispatchServiceTest`、`DispatchScoringServiceTest`、`AgentServiceTest`、`WorkloadServiceTest` | 推荐排序、权重、并发分配、负载 |
| AI | `AiAnalysisServiceTest`、`AiSuggestionServiceTest`、`AiSessionServiceTest`、`AiTicketAccessGuardTest`、`AiEndpointAuthTest` | Mock AI 客户端、非法输出、越权、采纳 |
| 分析 | 各 `*AnalyticsServiceTest`、`AnalyticsQueryTest`、`ReportServiceTest` | 区间校验、聚合口径、报表 |
| 健康与追踪 | `HealthControllerTest`、`RequestIdFilterTest` | 就绪状态映射、请求 ID |
| 知识库/用户 | 本次补充（见测试报告） | 补齐审计指出的空白 |
| Python | 33 个测试 | Prompt、Schema、错误码、限流、健康检查 |
| 前端 | 类型检查 + 生产构建 | 未引入组件/E2E 测试框架 |

### 已知不足

1. 前端没有组件级单测与 E2E；关键页面依赖类型检查、构建与人工回归。引入 Vitest/Playwright 会增加依赖与维护成本，当前项目规模下先保证构建门禁。
2. 没有覆盖率门禁（未接 JaCoCo）；测试数量不等于覆盖率。
3. 没有容器镜像构建 job；可在后续加 `docker build` 校验，当前本机无 Docker。

## 5. 失败排查

| 失败步骤 | 常见原因 | 处理 |
| --- | --- | --- |
| `./mvnw` 无执行权限 | Linux 下未 `chmod` | 已在 workflow 中显式 `chmod +x mvnw` |
| Maven 下载慢/失败 | 网络或镜像问题 | 重跑；必要时在 CI 配置 Maven 镜像 |
| `npm ci` 失败 | `package-lock.json` 与 `package.json` 不一致 | 本地执行 `npm install` 更新锁文件后提交 |
| `vue-tsc` 报类型错误 | 新增代码类型不完整 | 本地先跑 `vue-tsc --noEmit` |
| `pytest` 失败 | 新增用例未 Mock 模型调用 | 用 `monkeypatch` 替换 `app.llm` 调用，禁止真实请求 |
| 测试通过但打包失败 | 资源/主类问题 | 本地 `mvnw clean package` 复现 |

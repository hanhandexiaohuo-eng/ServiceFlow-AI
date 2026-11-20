# 第七阶段全量回归测试报告

执行日期：2026-11-17
执行环境：Windows 11 + PowerShell 5.1，项目目录 `C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai`

| 项 | 实际值 |
| --- | --- |
| JDK | OpenJDK 21.0.11（`D:\JDK21`），编译目标 17 |
| 构建工具 | Maven Wrapper 3.9.x（`backend-java\mvnw.cmd`） |
| Node.js / npm | Node v24.16.0 / npm 11.x（本机 `npm.ps1` 入口损坏，使用 `node` 直接调用） |
| Python | 3.11（`ai-python\.venv`） |
| MySQL | MySQL 8.4，本机服务运行中（3306） |
| Docker | **未安装**（`docker version` 不可用），容器相关项未实测 |

> 本报告只记录本轮（2026-11-17）真实执行过的命令与结果。未执行、无法执行的项一律标注「未测试」并说明原因，不做推测性结论。

## 1. 结果总览

| 类别 | 结果 | 证据 |
| --- | --- | --- |
| Java 单元测试 | **通过**：28 个测试类、172 个用例，Failures 0 / Errors 0 / Skipped 0 | surefire-reports 汇总 |
| Java 打包 | **通过**：可执行 fat jar 34,729,617 字节 | 干净副本构建后回填 `target` |
| Python 测试 | **通过**：33 passed（1 条 Starlette/httpx 弃用告警） | `pytest -q` |
| 前端类型检查 | **通过**：`vue-tsc --noEmit` 退出码 0 | 实跑 |
| 前端生产构建 | **通过**：产物分包正常（入口 36.1 KB / vue 96.2 KB / echarts 555.7 KB） | `vite build` 产物 |
| 运行时接口（新代码） | **通过**：Swagger、请求 ID、就绪检查含 AI、DEGRADED、CORS、慢 SQL、鉴权拦截均实测 | 8090/8091 临时实例，见第 6 节 |
| 接口系统测试 ×50 | **通过**：50 轮共 400 次请求，失败 0，通过率 100% | 见第 9 节 |
| 登录成功与核心业务 UI 回归 | **未测试**：无可用账号密码，未做浏览器端到端操作 | — |
| Docker 部署 | **未测试**：本机无 Docker，未构建镜像、未启动容器 | — |
| CI 远程执行 | **未测试**：未推送 GitHub | — |

## 2. Java 后端

### 2.1 执行命令

```powershell
$env:JAVA_HOME = 'D:\JDK21'
.\mvnw.cmd -B clean package
```

### 2.2 测试结果

- 测试类 28 个，覆盖鉴权、工单、工作流、SLA、调度、AI 分析/会话/建议、知识库、分析报表、健康检查、请求 ID 过滤器等。
- 汇总：`files=28 tests=172 failures=0 errors=0 skipped=0`。
- 本轮新增测试：`RequestIdFilterTest`（3 用例）、`KnowledgeServiceTest`（12 用例）、`UserQueryServiceTest`（4 用例），均不依赖真实 MySQL。

### 2.3 打包过程中的真实问题（如实记录）

1. 在主目录执行 `clean package` 时，测试阶段 172 个用例全部通过，但 `spring-boot:repackage` 失败：
   `Unable to rename '...serviceflow-backend-0.1.0.jar' to '...jar.original'`，原因是旧的 `java -jar` 进程仍在运行并占用该文件。
2. 规避方式：将 `backend-java`（排除 `target`、`.git`）复制到工作区外的临时目录 `.build\backend-java`，在干净副本执行同一条命令，构建成功（exit=0）。
3. 将副本生成的 34,729,617 字节 fat jar 复制回 `backend-java\target\serviceflow-backend-0.1.0.jar`（覆盖成功），交付物与源码一致。
4. 未终止用户正在运行的旧后端进程（PID 50984），未修改数据库。

> 结论：源码可干净构建、测试全绿；主目录首次失败是文件占用导致的打包步骤问题，不是代码或测试失败，但该现象必须如实记录。

## 3. Python AI 服务

命令与结果：

```powershell
cd ai-python
.\.venv\Scripts\python.exe -m pytest -q
# 33 passed, 1 warning in 0.98s
```

- 模型调用在测试中全部 Mock，不消耗 DeepSeek 额度，不依赖真实 API Key。
- 唯一告警为第三方库 `starlette.testclient` 对 `httpx` 的弃用提示，不影响结果。

## 4. 前端

### 4.1 类型检查

```powershell
node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit
# tsc_exit=0
```

### 4.2 生产构建

```powershell
node .\node_modules\vite\bin\vite.js build
```

构建产物关键文件（`frontend\dist\assets`）：

| 文件 | 大小 |
| --- | --- |
| `index-Da3oBkdl.js` | 36,085 B |
| `vue-RBcRceyz.js` | 96,166 B |
| `echarts-DWeXfsGe.js` | 555,695 B |
| `index-CJIorxz6.css` | 13,982 B |

- 路由全部改为懒加载后，单个入口包从优化前约 895 KB 降为 36 KB，第三方库独立成块；每个业务视图独立 chunk（如 `TicketsView`、`TicketBoardView`、`AiWorkspaceView`）。
- 开发服务器 `http://127.0.0.1:5173/` 本轮实测返回 HTTP 200。

## 5. 配置、安全与 CI 文件校验

| 检查项 | 结果 |
| --- | --- |
| `docker-compose.yml` 语法与结构 | 通过：services = `ai / backend / frontend / migrate / mysql`，volumes = `mysql-data / backend-logs` |
| `.github/workflows/ci.yml` 语法与结构 | 通过：name=`CI`，jobs = `backend / python / frontend`；工作流内无真实密钥 |
| 仓库是否跟踪真实 `.env` | 未跟踪；`git check-ignore` 命中 `.env` 与 `deploy/.env` |
| `.env.example` / `deploy/.env.example` | 仅占位符，无真实密码或 API Key |
| 前端环境文件 | `.env.development`、`.env.production` 已通过 `!` 规则纳入版本管理，内容不含敏感值 |

## 6. 运行时验证（已在 8090/8091 临时实例完成）

### 6.1 验证方式

- 使用本轮构建的 fat jar（`target\serviceflow-backend-0.1.0-app.jar`）在 **8090** 启动一份新代码实例，连接本机 MySQL 与 8000 端口的 Python 服务。
- 另在 **8091** 启动第二份实例，把 `AI_SERVICE_BASE_URL` 指向不可达端口，用于验证 DEGRADED 分支。
- 两个临时实例验证完成后已停止；用户原有 8080 进程（旧构建）未改动，仍返回 200。
- 验证所用的数据库密码只存在于进程环境变量，未写入任何文件、文档或提交内容。

### 6.2 实测结果

| # | 验证项 | 结果 | 实际证据 |
| --- | --- | --- | --- |
| 1 | `GET /api/health` | 通过 | HTTP 200，`status=UP` |
| 2 | `GET /api/health/ready`（全依赖正常） | 通过 | HTTP 200，`status=UP`，`checks` 含 `mysql=UP`、`ai=UP`（“数据库连接正常 / AI 服务可用”） |
| 3 | `GET /api/health/ready`（AI 不可达） | 通过 | HTTP 200，`status=DEGRADED`，`ai=DOWN`，message 只含异常类型 `ResourceAccessException`，不泄露内部细节 |
| 4 | `/v3/api-docs` | 通过 | HTTP 200；共 56 个 path、17 个 tag，`components.securitySchemes` 含 `bearerAuth` |
| 5 | `/swagger-ui/index.html` | 通过 | HTTP 200 |
| 6 | 自定义 `X-Request-Id` 透传 | 通过 | 请求头 `verify-abc12345` 原样返回，日志 MDC 同样为该值 |
| 7 | 未带请求 ID 时自动生成 | 通过 | 响应头返回 UUID，如 `ef0dd8e6-...` |
| 8 | 访问日志 | 通过 | `access method=POST path=/api/auth/login status=401 costMs=78 requestId=... user=-` |
| 9 | 慢 SQL 日志 | 通过 | 以 `SLOW_SQL_THRESHOLD_MS=1` 运行：`slow_sql costMs=180 method=queryForObject sql=SELECT COUNT(*) FROM users`（只记 SQL，不记参数） |
| 10 | CORS 白名单 | 通过 | `CORS_ALLOWED_ORIGINS=http://localhost:5173` 时返回 `Access-Control-Allow-Origin: http://localhost:5173` 与 `Access-Control-Expose-Headers: X-Request-Id`；非白名单 Origin 不返回 ACAO |
| 11 | 未认证访问受保护接口 | 通过 | `GET /api/tickets` → HTTP 401 |
| 12 | 错误密码登录 | 通过 | `POST /api/auth/login`（错误密码）→ HTTP 401 |
| 13 | 请求体校验 | 通过 | 空 JSON `{}` → HTTP 400 |

### 6.3 未覆盖的运行时项

| # | 项 | 原因 |
| --- | --- | --- |
| 1 | 登录成功后的工单创建、流转、调度、AI 采纳、知识库、报表 | 没有可用账号密码，未做 UI 端到端操作 |
| 2 | MySQL 宕机时 `/api/health/ready` 返回 503 | 未停止用户的 MySQL 服务 |
| 3 | 生产端口 8080 的新代码运行 | 用户旧进程仍在 8080 运行旧构建；新代码已在 8090 验证，功能等价 |

## 7. 已知问题与风险

1. **启动依赖数据库**：`AuthService#run` 作为 `ApplicationRunner` 直接调用 `userRepository.count()`，数据库不可达时应用启动失败（已实测 `Access denied` 与连接超时两种情形）。若希望“进程先启动、由 ready 报告 DOWN”，需要为初始化增加异常兜底。
2. **构建时文件占用**：Windows 下运行中的 jar 无法被 repackage 重命名，需先停止旧进程，或在独立目录构建。
3. **非法 JSON 返回 500**：请求体 JSON 格式错误时，`HttpMessageNotReadableException` 未被 `GlobalExceptionHandler` 单独处理，落到兜底 500（实测）。属于已有行为，本轮未改动。
4. **Docker 未实测**：镜像、健康检查、迁移与卷持久化仅完成文件与静态校验。
5. **凭据提示**：本次验证使用的数据库密码曾出现在对话中，如该密码在其他环境复用，建议轮换；代码、文档与镜像中均未写入该值。

## 8. 复验命令（把新代码切换到 8080）

```powershell
Set-Location C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai\backend-java
$env:JAVA_HOME = 'D:\JDK21'

# 停止旧的本项目后端进程（不影响其他 Java 程序）
Get-CimInstance Win32_Process -Filter "name='java.exe'" |
  Where-Object { $_.CommandLine -like '*serviceflow-backend*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

$secure = Read-Host '请输入 serviceflow 用户密码（不回显）' -AsSecureString
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:MYSQL_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

$env:MYSQL_HOST = '127.0.0.1'; $env:MYSQL_PORT = '3306'
$env:MYSQL_DATABASE = 'serviceflow'; $env:MYSQL_USER = 'serviceflow'
$env:JWT_SECRET = '<至少 32 字节随机串>'
$env:AI_SERVICE_BASE_URL = 'http://127.0.0.1:8000'
& 'D:\JDK21\bin\java.exe' -jar .\target\serviceflow-backend-0.1.0.jar
```

启动后按 [演示指南](demo-guide.md) 完成登录与核心业务流程回归；浏览器端验证建议记录页面与状态码。

## 9. 接口系统测试 ×50（2026-11-17）

- 被测对象：本轮构建的 fat jar，8090 端口临时实例，连接本机 MySQL 与 8000 端口 Python 服务。
- 测试方法：单轮依次请求 8 个检查点，循环 50 轮，共 400 次请求；每轮校验状态码、响应体关键字段与响应头。
- 检查点：`/api/health`、`/api/health/ready`、`/v3/api-docs`、`/swagger-ui/index.html`、未认证 `/api/tickets`、错误密码登录、白名单 CORS、非白名单 CORS。

| 检查点 | 请求数 | 失败 | 平均耗时 | P95 | 最大耗时 |
| --- | --- | --- | --- | --- | --- |
| `/api/health` | 50 | 0 | 1.0 ms | 2.3 ms | 2.3 ms |
| `/api/health/ready` | 50 | 0 | 4.7 ms | 6.3 ms | 48.9 ms |
| `/v3/api-docs` | 50 | 0 | 22.0 ms | 17.7 ms | 890.1 ms |
| `/swagger-ui/index.html` | 50 | 0 | 2.4 ms | 3.5 ms | 19.0 ms |
| 未认证 `/api/tickets` | 50 | 0 | 1.7 ms | 1.8 ms | 33.0 ms |
| 错误密码登录 | 50 | 0 | 59.7 ms | 58.0 ms | 320.9 ms |
| 白名单 CORS | 50 | 0 | 1.5 ms | 2.2 ms | 6.4 ms |
| 非白名单 CORS | 50 | 0 | 1.0 ms | 1.9 ms | 2.7 ms |

结论与观察：

- 400 次请求全部符合预期，通过率 100%；每轮自定义 `X-Request-Id` 均被原样返回。
- 登录接口平均约 60 ms，符合 BCrypt 慢哈希的预期成本；`/v3/api-docs` 的最大 890 ms 出现在首次生成文档时，后续稳定在 20 ms 左右。
- 测试期间应用日志 `ERROR` 与 `WARN` 均为 0 条。
- 该测试只读接口，不写入业务数据；实例在测试结束后已停止，用户 8080 旧进程未被改动。

## 10. 结论

- 代码层面：Java 172 个用例、Python 33 个用例、前端类型检查与生产构建全部通过，打包产物可用。
- 运行时层面：第 6 节的 13 项接口行为均在新代码上实测通过；第 9 节的 50 轮 400 次接口系统测试通过率 100%，期间无 ERROR/WARN 日志。
- 仍未完成：登录成功后的业务 UI 回归、MySQL 宕机 503、Docker 实测、CI 远程执行。上述项在完成前不应宣称“已部署成功”或“已全量回归通过”。

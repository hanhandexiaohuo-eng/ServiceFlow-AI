# ServiceFlow AI 部署指南

> **重要声明**：本机当前未安装 Docker（`docker` 命令不存在）。方式 A 的镜像构建、容器启动与健康检查均**未实测**，本文只提供与仓库文件一致、可复现的步骤；方式 B 的命令来自本仓库 README 与 `docs/phase7-deployment.md`。
> 本文不包含任何真实密码或 API Key，所有敏感值只写占位符。

## 1. 前置条件

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| JDK | 17+ | `pom.xml` 的 `java.version=17`；本仓库已在 JDK 21（`D:\JDK21`）验证 |
| Node.js | 20+ | `frontend/Dockerfile` 使用 `node:20-alpine`；第六阶段实测 Node v24.16.0 |
| Python | 3.11+ | `ai-python/Dockerfile` 使用 `python:3.11-slim` |
| MySQL | 8.4 | Compose 镜像 `mysql:8.4`，字符集 `utf8mb4` |
| Docker Engine + Compose v2 | 仅方式 A 需要 | **本机未安装，方式 A 未实测** |
| Maven | 无需单独安装 | 使用仓库自带 `backend-java\mvnw.cmd` |
| 可用端口 | 5173 / 8080 / 8000 / 3306 / 8085 | 前端开发 5173、Java 8080、Python 8000、MySQL 3306、容器前端 8085 |

## 2. 目录说明

```text
serviceflow-ai/
├─ frontend/          Vue 3 + Vite + TypeScript（开发 5173，容器内 nginx:80）
├─ backend-java/      Spring Boot 3.5 + JdbcTemplate（8080，Maven Wrapper）
├─ ai-python/         FastAPI + Uvicorn（8000，.venv 为本机虚拟环境）
├─ deploy/            init/ 初始化 SQL、migrations/ 迁移 SQL、.env.example、仅 MySQL 的 Compose
├─ docs/              阶段文档、安全、部署、数据库设计
├─ docker-compose.yml 全栈编排：mysql / migrate / ai / backend / frontend
├─ .env.example       本机手动启动的环境变量占位符
└─ README.md
```

## 3. 方式 A：Docker Compose 一键部署（未实测）

### 3.1 准备环境变量

在仓库根目录执行：

```powershell
Copy-Item deploy\.env.example .env
```

按 `deploy/.env.example` 填写，至少包含：

| 变量 | 是否必填 | 说明 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | 必填 | Compose 用 `${MYSQL_ROOT_PASSWORD:?}` 强制要求 |
| `MYSQL_PASSWORD` | 必填 | 业务账号密码，Compose 强制要求 |
| `JWT_SECRET` | 必填 | 至少 32 字节；不足时 `JwtService` 拒绝启动 |
| `ADMIN_INITIAL_PASSWORD` | 建议填写 | 仅当 `users` 表为空时创建 `admin`；代码要求至少 6 位，生产建议 12 位以上 |
| `DEEPSEEK_API_KEY` | 可选 | 留空时 AI 功能调用失败，但其他业务可用 |
| `MYSQL_DATABASE` / `MYSQL_USER` | 可选 | 默认 `serviceflow` / `serviceflow` |
| `FRONTEND_PORT` | 可选 | 默认 `8085`，只绑定 `127.0.0.1` |

`.env` 已被 `.gitignore` 忽略，`deploy/.env.example` 只有占位符。密钥只注入运行时容器，不进入镜像层。

### 3.2 构建并启动

```powershell
docker compose --env-file .env up -d --build
```

启动顺序由 `depends_on` 条件保证：`mysql healthy` → `migrate` 执行 phase5/phase6 并退出 0 → `ai healthy` → `backend healthy` → `frontend`。

### 3.3 验证清单

| # | 命令 / 操作 | 期望 |
| --- | --- | --- |
| 1 | `docker compose ps` | 5 个服务存在；mysql/ai/backend/frontend 为 `healthy`，migrate 为 `exited (0)` |
| 2 | `curl.exe -s http://127.0.0.1:8085/healthz` | 返回 `ok` |
| 3 | `curl.exe -s http://127.0.0.1:8085/api/health/ready` | `status=UP`（AI 不可达时为 `DEGRADED`，仍 HTTP 200） |
| 4 | 浏览器打开 `http://127.0.0.1:8085` | 出现登录页，静态资源无 404 |
| 5 | 用 `admin` + `ADMIN_INITIAL_PASSWORD` 登录 | 返回 Token 并进入系统 |
| 6 | 创建工单后刷新详情 | 数据持久化，时间线有 CREATE 事件 |
| 7 | `docker compose restart` 后再查 | 工单仍存在（`mysql-data` 卷生效） |
| 8 | 配置 Key 后触发 AI 分析 | 返回结构化结果；未配置时返回明确 AI 错误 |

### 3.4 停止、重启、日志与备份

```powershell
docker compose logs -f backend       # 查看后端日志
docker compose restart backend       # 重启单个服务
docker compose down                  # 停止并保留 mysql-data / backend-logs
docker compose down -v               # 停止并删除数据卷（清空数据库，谨慎）
```

备份与恢复（服务名 `mysql` 与根 `docker-compose.yml` 一致）：

```powershell
cmd /c 'docker compose exec mysql sh -c "mysqldump -u root -p$MYSQL_ROOT_PASSWORD serviceflow" > backup.sql'
cmd /c 'docker compose exec -T mysql sh -c "mysql -u root -p$MYSQL_ROOT_PASSWORD serviceflow" < backup.sql'
```

`$MYSQL_ROOT_PASSWORD` 由容器内 `sh` 展开；用 `cmd /c` 包一层是为了让 PowerShell 5 正确处理 `<` / `>` 重定向并保持字节编码。恢复会覆盖目标库同名表数据，执行前先确认目标库与环境。

### 3.5 升级与迁移注意事项

- **空数据卷首次启动**：`deploy/init/01~05` 自动执行，覆盖 phase1~4（建库、工单、用户、事件、人员/技能/调度表与种子）。
- **migrate 服务**：只执行 `phase5-ai-knowledge.sql` 与 `phase6-analytics-indexes.sql`，两者可重复执行；`phase4` 不再自动执行，避免重复插入种子。
- **已有旧库**：若数据卷来自 phase2 或更早，需先按顺序手工执行 `phase3`、`phase4`，再让 `migrate` 执行 phase5/6。`phase4` 在部署文档中标注为旧库一次性执行。
- **重建镜像**：代码更新后执行 `docker compose --env-file .env up -d --build`。若一次性 `migrate` 容器已存在且未重建，可先 `docker compose down`（保留卷）再 `up`，或执行 `docker compose run --rm migrate`（未实测）。
- **禁止**在生产执行 `docker compose down -v`。
- MySQL 的 `docker-entrypoint-initdb.d` 只在空数据卷生效，已有 `mysql-data` 不会重跑 init。

## 4. 方式 B：本机三端手动启动（PowerShell）

以下命令与本仓库 README / `docs/phase7-deployment.md` 一致。Spring Boot **不会自动读取 `.env`**，每个终端需显式设置同名环境变量。

### 4.1 准备数据库

本机 MySQL 8.4 需已有 `serviceflow` 库和 `MYSQL_USER/MYSQL_PASSWORD` 对应账号及权限（Compose 会自动创建账号，手工 MySQL 需自行创建）。全新库按顺序执行：

```powershell
cmd /c "mysql -u root -p < deploy\init\01-init.sql"
cmd /c "mysql -u root -p < deploy\init\02-tickets.sql"
cmd /c "mysql -u root -p < deploy\init\03-users.sql"
cmd /c "mysql -u root -p < deploy\init\04-ticket-events.sql"
cmd /c "mysql -u root -p < deploy\init\05-agent-dispatch.sql"
cmd /c "mysql -u root -p serviceflow < deploy\migrations\20261116-phase5-ai-knowledge.sql"
cmd /c "mysql -u root -p serviceflow < deploy\migrations\20261116-phase6-analytics-indexes.sql"
```

> PowerShell 5 不支持 `<` 输入重定向，因此用 `cmd /c` 包一层。`02~05` 脚本内部已有 `USE serviceflow`。旧库升级只需补跑 phase3、phase4、phase5、phase6，不要重复跑 init。

### 4.2 启动 Python AI（8000）

```powershell
cd ai-python
$env:DEEPSEEK_API_KEY = '<DeepSeek Key，可留空>'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
```

若 `.venv` 不存在或依赖缺失，先执行 `.\.venv\Scripts\python.exe -m pip install -r requirements.txt`。

### 4.3 启动 Java 后端（8080）

```powershell
cd backend-java
$env:JAVA_HOME = 'D:\JDK21'
$env:MYSQL_PASSWORD = '<数据库密码>'
$env:JWT_SECRET = '<至少 32 字节随机串>'
$env:ADMIN_INITIAL_PASSWORD = '<初始管理员密码>'
.\mvnw.cmd spring-boot:run
```

默认 `SPRING_PROFILES_ACTIVE=dev`、端口 8080；数据库默认 `127.0.0.1:3306/serviceflow`。`JWT_SECRET` 缺失或不足 32 字节会直接启动失败（fail-fast）。`admin` 只在 `users` 表为空时创建。

### 4.4 启动前端（5173）

```powershell
cd frontend
node .\node_modules\vite\bin\vite.js
```

Vite 固定绑定 `127.0.0.1:5173`（`strictPort=true`），`/api` 代理到 `VITE_PROXY_TARGET`，默认 `http://127.0.0.1:8080`。本仓库 `frontend\node_modules` 已存在，因此不需要 npm；若目录缺失，需使用可用的 `npm.cmd install` 安装（**不假设 npm 可用**）。

### 4.5 启动顺序建议

MySQL → Java 后端 → Python AI → 前端。后端可在数据库/AI 暂不可用时启动，由 `/api/health/ready` 如实报告；但业务读写需要 MySQL。

## 5. 环境变量完整表

| 变量 | 默认值 | 使用方 | 说明 |
| --- | --- | --- | --- |
| `MYSQL_HOST` | `127.0.0.1` | Java | 容器内为 `mysql` |
| `MYSQL_PORT` | `3306` | Java | MySQL 端口 |
| `MYSQL_DATABASE` | `serviceflow` | Java / Compose | 库名 |
| `MYSQL_USER` | `serviceflow` | Java / Compose | 业务账号 |
| `MYSQL_PASSWORD` | 空（无默认） | Java / Compose | 必填 |
| `MYSQL_ROOT_PASSWORD` | 无 | Compose | 仅 MySQL 容器 root 密码 |
| `SERVER_PORT` | `8080` | Java | 后端端口 |
| `SPRING_PROFILES_ACTIVE` | `dev` | Java | `dev/test/prod`；Compose 默认 `prod` |
| `JWT_SECRET` | 无 | Java | ≥32 字节，缺失拒绝启动 |
| `JWT_EXPIRE_MINUTES` | `120` | Java | Token 有效期 |
| `ADMIN_INITIAL_PASSWORD` | 空 | Java | 初始管理员密码，仅空表时使用 |
| `SLA_WARNING_MINUTES` | `120` | Java | SLA 预警阈值 |
| `SLA_URGENT_HOURS` | `4` | Java | URGENT 响应时限 |
| `SLA_HIGH_HOURS` | `8` | Java | HIGH 响应时限 |
| `SLA_MEDIUM_HOURS` | `24` | Java | MEDIUM 响应时限 |
| `SLA_LOW_HOURS` | `72` | Java | LOW 响应时限 |
| `CORS_ALLOWED_ORIGINS` | 空 | Java | 逗号分隔白名单；空=不开放跨域 |
| `SPRINGDOC_ENABLED` | `true` | Java | OpenAPI JSON 开关 |
| `SPRINGDOC_UI_ENABLED` | `true` | Java | Swagger UI 开关 |
| `SLOW_SQL_THRESHOLD_MS` | `500` | Java | 慢 SQL 告警阈值 |
| `LOG_FILE` | `logs/serviceflow-backend.log` | Java（prod） | 日志文件路径 |
| `AI_SERVICE_BASE_URL` | `http://127.0.0.1:8000` | Java | Python 服务地址；Compose 为 `http://ai:8000` |
| `AI_SERVICE_CONNECT_TIMEOUT_MS` | `3000` | Java | AI 连接超时 |
| `AI_SERVICE_READ_TIMEOUT_MS` | `90000` | Java | AI 读超时 |
| `AI_SERVICE_ENABLED` | `true`（test 为 `false`） | Java | 是否调用外部 AI |
| `AI_DEDUPE_SECONDS` | `60` | Java | AI 请求去重窗口 |
| `AI_USER_RATE_LIMIT_PER_MINUTE` | `10` | Java | 单用户每分钟 AI 调用上限 |
| `DEEPSEEK_API_KEY` | 空 | Python | 只注入 Python，不下发前端/Java |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | Python | 模型服务地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | Python | 模型名 |
| `DEEPSEEK_TIMEOUT_SECONDS` | `60` | Python | 模型调用超时 |
| `AI_RATE_LIMIT_PER_MINUTE` | `12` | Python | 进程内限流 |
| `DEEPSEEK_RETRY_DELAY_SECONDS` | `0.5` | Python | 代码默认值，示例文件未列 |
| `VITE_API_BASE_URL` | 空 | 前端构建期 | 空=同源（Vite 代理 / nginx 反代） |
| `VITE_PROXY_TARGET` | `http://127.0.0.1:8080` | Vite 开发 | `/api` 代理目标 |
| `FRONTEND_PORT` | `8085` | Compose | 宿主机前端端口，绑定 `127.0.0.1` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | Compose / Dockerfile | JVM 参数 |

## 6. 健康检查与就绪语义

| 组件 | 检查 | 语义 |
| --- | --- | --- |
| mysql | `mysqladmin ping`（Compose） | 不健康则后续服务不启动 |
| migrate | 容器退出码 | `exited (0)` 才允许 backend 启动 |
| ai | `GET /health`（Compose） | 只证明进程存活；`/health/ready` 在缺 Key 或 Prompt 加载失败时返回 503 |
| backend | `GET /api/health` / `GET /api/health/ready` | `/api/health` 只看进程；`/ready` 执行真实 `SELECT 1` 并探测 AI |
| frontend | `GET /healthz`（nginx） | 本地返回 200 `ok` |

`/api/health/ready` 的三种结果：

- **UP**：MySQL 正常，AI `/health` 可达。
- **DEGRADED**：MySQL 正常、AI 不可达；HTTP 仍为 **200**，核心业务可用，仅 AI 功能受影响。
- **DOWN**：MySQL 不可用；HTTP **503**，必须修复数据库。

注意：Java 只探测 Python 的 `/health`，该接口不校验 `DEEPSEEK_API_KEY`；因此“未配置 Key”不会让 Java 就绪变成 DEGRADED，而是实际调用 AI 时返回错误（响应头 `X-AI-Error-Code`）。

## 7. 常见故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `MYSQL_PASSWORD is required` | 未传 `.env` | 使用 `--env-file .env`，确认根目录 `.env` 存在 |
| backend 一直 unhealthy | MySQL 未就绪或密码错误 | `docker compose logs mysql` / `logs backend`，核对 `MYSQL_*` |
| `/api/health/ready` 返回 503 | 数据库不可用 | 看 `checks` 中 `mysql.message`，检查端口、账号、库名 |
| `/api/health/ready` 返回 DEGRADED | Python AI 不可达 | 检查 `ai` 容器或本机 8000 端口 |
| 前端 502 | backend 未 healthy | 先 `docker compose ps`，再看 backend 日志 |
| 前端 401 | Token 过期或用户被禁用 | 重新登录；检查 `users.status` |
| 后端启动即退出 | `JWT_SECRET` 缺失或不足 32 字节 | 设置高熵随机串后重启 |
| AI 分析返回 503/错误 | `DEEPSEEK_API_KEY` 未配置或 AI 服务未启动 | 配置 Key 后重启 ai/backend |
| 8085 被占用 | 端口冲突 | `.env` 设 `FRONTEND_PORT=8090` |
| `npm` 报错 / 不可用 | 本机 npm 环境异常 | 直接使用 `node .\node_modules\vite\bin\vite.js` |
| SQL 脚本导入乱码 | 客户端字符集不一致 | 使用 MySQL 8 默认 `utf8mb4` 客户端执行 |

## 8. 生产注意事项

- **密钥轮换**：数据库密码、`JWT_SECRET`、DeepSeek Key 均只从环境变量注入。更换 `JWT_SECRET` 会使已签发 Token 全部失效；历史对话/日志中出现过的凭据应视为泄露并按 `docs/phase7-security.md` 第 4 节轮换。
- **CORS 默认关闭**：`cors.allowed-origins` 为空时不注册跨域映射。前后端同源（nginx 反代）无需配置；确需跨域时只列明确来源，禁止 `*`。
- **Swagger 开关**：默认开启；生产设置 `SPRINGDOC_ENABLED=false`、`SPRINGDOC_UI_ENABLED=false`。
- **日志目录**：后端写 `logs/serviceflow-backend.log`（相对启动目录），Compose 挂载 `backend-logs:/app/logs`。仓库未见日志滚动配置，生产需用挂载卷 + 外部轮转/采集，且不得打印密码或 Token。
- **端口最小化**：根 Compose 只把 frontend 映射到 `127.0.0.1:${FRONTEND_PORT}:80`，MySQL/Java/Python 仅在内部网络可达；`deploy/docker-compose.yml` 的 MySQL 也仅绑定回环地址，属于开发用途。
- **TLS**：`frontend/nginx.conf` 只监听 80，未配置证书；公网部署需在外层负载均衡/反向代理终止 TLS。
- **运行身份**：backend 镜像使用非 root 用户 `app`，ai 镜像使用 uid 10001；nginx 基于官方镜像。
- **资源与超时**：`JAVA_OPTS` 默认 `-XX:MaxRAMPercentage=75`；nginx `/api` 读超时 120s，需覆盖 Java 侧 90s 的 AI 读超时。
- **AI 限流**：Python 限流为进程内窗口，多副本会按副本数放大；多副本时应改为网关限流。
- **数据保护**：定期备份 `mysql-data` 对应库；`docker compose down -v` 会删除数据卷，生产禁用。

**未实测项**：Docker 镜像构建、Compose 启动、健康检查、`migrate` 一次性任务、卷持久化与备份恢复命令均需在安装 Docker 的环境按本文步骤执行后回填结果。

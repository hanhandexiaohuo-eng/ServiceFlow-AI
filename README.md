# ServiceFlow AI

AI 智能工单与服务调度平台。通过 AI 对工单做分类、信息提取与处理建议生成，帮助服务团队更快定位问题、分派任务并闭环处理。

当前为**第一阶段**：项目骨架 + 三个可独立运行的服务 + MySQL 连接与健康检查验证。尚未包含登录鉴权、工单业务、Redis、消息队列与 AI 工作流。

## 架构

| 模块 | 技术栈 | 端口 | 职责 |
| --- | --- | --- | --- |
| `frontend` | Vue 3 + Vite + TypeScript | 5173 | 管理端页面，经 `/api` 代理访问后端 |
| `backend-java` | Java 17 + Spring Boot 3.5 + Maven | 8080 | 业务入口、数据库访问、健康检查 |
| `ai-python` | Python 3.11+ + FastAPI + Uvicorn | 8000 | AI 能力服务（本阶段仅健康检查） |
| `deploy` | MySQL 8.4 + Docker Compose | 3306 | 本地数据库（仅本机回环可访问） |

```
serviceflow-ai/
├── frontend/          # Vue 3 + Vite + TS 首页与 API 模块
├── backend-java/      # Spring Boot 后端（健康检查 + MySQL 探测）
├── ai-python/         # FastAPI AI 服务
├── deploy/            # MySQL Docker Compose、.env.example、初始化 SQL
├── docs/              # 阶段验收记录
├── README.md
├── AGENTS.md          # 架构约束、编码规范、测试与密钥要求
└── .gitignore
```

## 环境要求

- Node.js 20+（已在 Node 24.16 验证）与 npm 10+
- JDK 17+（已在 JDK 21.0.11 验证）；项目通过 `java.version=17` 以 17 为目标编译
- Python 3.11+（已在 3.11.9 验证）
- MySQL 8.4（本机服务或 Docker）
- Maven 3.9+：**无需单独安装**，项目自带 Maven Wrapper `backend-java\mvnw.cmd`
- 可选：Docker Desktop（用于 `deploy/docker-compose.yml`）

## 快速开始（Windows PowerShell）

所有命令都在 PowerShell 中执行，路径按需替换。**不要把 Linux 命令直接当 PowerShell 用。**

### 1. 数据库

方式 A：Docker（若已安装 Docker Desktop）

```powershell
Set-Location .\serviceflow-ai\deploy
Copy-Item .env.example .env
notepad .env          # 填写 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD
docker compose up -d
docker compose ps
```

方式 B：使用本机已安装的 MySQL 8.4 服务（Docker 不可用时）

```powershell
# 用 root 登录后执行（密码按提示输入，不要写进脚本）
& "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -u root -p
```

```sql
CREATE DATABASE IF NOT EXISTS serviceflow
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'serviceflow'@'localhost' IDENTIFIED BY '替换为你的密码';
GRANT ALL PRIVILEGES ON serviceflow.* TO 'serviceflow'@'localhost';
FLUSH PRIVILEGES;
```

### 2. Java 后端（8080）

先设置数据库环境变量。密码用不回显的方式输入，且只对当前 PowerShell 窗口有效：

```powershell
$secure = Read-Host "请输入 serviceflow 用户的 MySQL 密码（输入时不显示）" -AsSecureString
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:MYSQL_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "serviceflow"
$env:MYSQL_USER = "serviceflow"
$env:JAVA_HOME = "D:\JDK21"           # 指向本机 JDK 17+ 的安装目录
```

> `deploy\.env` 只供 Docker Compose 使用，Spring Boot **不会**自动读取它；Java 只认进程环境变量。换一个 PowerShell 窗口后需要重新设置。

```powershell
Set-Location .\serviceflow-ai\backend-java
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

也可以直接运行打包产物：

```powershell
$env:JAVA_HOME = "D:\JDK21"
# 显式使用 JDK 21，避免 PATH 上的其他 JDK 导致运行时行为不一致
& "D:\JDK21\bin\java.exe" -jar .\target\serviceflow-backend-0.1.0.jar
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/api/health/ready
```

### 3. Python AI 服务（8000）

```powershell
Set-Location .\serviceflow-ai\ai-python
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m app.main
```

等价的 uvicorn 命令（如需热重载）：

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/health/ready
```

### 4. 前端（5173）

```powershell
Set-Location .\serviceflow-ai\frontend
npm install
npm run dev
```

打开 http://127.0.0.1:5173 ，点击“检查后端连接”，页面会展示 Java 后端 `/api/health` 的真实响应；后端未启动时会显示明确的错误提示。

> 本机若出现 `npm : Cannot find module ... npm-prefix.js`，说明 `npm.ps1` 入口损坏，改用 `npm.cmd install`、`npm.cmd run dev` 即可。

## 接口说明

| 服务 | 接口 | 说明 |
| --- | --- | --- |
| Java | `GET /api/health` | 存活检查，返回 `service / status / version / timestamp` |
| Java | `GET /api/health/ready` | 就绪检查，真实执行 `SELECT 1`；数据库不可用时返回 **503** 与 `checks[].status=DOWN` |
| Python | `GET /health` | 存活检查 |
| Python | `GET /health/ready` | 就绪检查（本阶段无外部依赖） |

前端开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`（见 `frontend/vite.config.ts`），因此不存在浏览器跨域问题，也不需要在后端配置 CORS。

## 端口占用排查

```powershell
Get-NetTCPConnection -LocalPort 5173,8080,8000,3306 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalPort, OwningProcess
Get-Process -Id <上一步的 OwningProcess>
```

优先确认占用者是什么服务，再决定是否调整本项目端口（前端改 `vite.config.ts`，后端改 `SERVER_PORT`），不要直接结束未知进程。

## 常见问题

- **`/api/health/ready` 返回 503**：数据库未启动、库/用户未创建或密码不匹配。响应里的 `checks[0].message` 会给出具体原因，且不会暴露密码。
- **报错 `Access denied ... (using password: NO)`**：说明启动进程里没有 `MYSQL_PASSWORD`（驱动根本没提交密码）。在该窗口先按上面的 `Read-Host -AsSecureString` 方式设置，再启动 Java；`(using password: YES)` 则表示已提交密码但值不对。
- **后端启动时报驱动或连接错误**：确认 `MYSQL_*` 环境变量与数据库实际配置一致；本项目设置 `initialization-fail-timeout=-1`，数据库暂时不可用时服务仍可启动，由 ready 接口如实报告。
- **JDK 版本不对**：先执行 `$env:JAVA_HOME = "D:\JDK21"`，再运行 `.\mvnw.cmd -v` 确认 Java version 为 17+。
- **MySQL 端口 3306 被占用**：把 `deploy\.env` 中的 `MYSQL_PORT` 改为 3307，并同步设置 `$env:MYSQL_PORT = "3307"`。
- **密钥安全**：所有真实密码只放 `deploy/.env` 或 PowerShell 环境变量；`.env` 已被 `.gitignore` 忽略。

## 验收记录

第一阶段的真实验收结果见 [docs/phase-1-acceptance.md](docs/phase-1-acceptance.md)；中断续接请看 [docs/next-session.md](docs/next-session.md)。

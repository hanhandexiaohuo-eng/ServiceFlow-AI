# 第一阶段验收记录

执行日期：2026-11-14（工作区清理后按交接文档复现项目并重新验收）
执行环境：Windows 11 + PowerShell 5.1，工作目录 `C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai`
运行 JDK：`D:\JDK21\bin\java.exe`（OpenJDK 21.0.11 LTS），构建时 `JAVA_HOME=D:\JDK21`

> **背景**：2026-10-26 完成的第一阶段成果未做 Git 提交，工作区清理后源码丢失。本次按 `docs/next-session.md` 与历史验收记录完整复现项目，并重新执行真实构建、启动与接口验证。本文档只记录本次（2026-11-14）实际执行的结果。

## 一、环境检查结果

| 项目 | 检测结果 | 结论 |
| --- | --- | --- |
| Java | OpenJDK 21.0.11 LTS（`D:\JDK21`） | 满足 |
| Maven | 未全局安装 | 使用项目内 Maven Wrapper（3.9.9） |
| Python | 3.11.9（`E:\python\python.exe`） | 满足 |
| Node.js / npm | Node 24.16.0；npm 11.13.0（本机 `npm.ps1` 入口损坏，使用 `npm.cmd`） | 满足 |
| MySQL | MySQL84 服务运行中，监听 3306 | 使用本机实例 |
| Docker | 未安装 | Docker Compose 未实测 |
| 端口 8080 | 被用户另一 IDEA 调试项目（sky-take-out）占用 | 未终止该进程；本项目验证时使用 `SERVER_PORT=8081` |

## 二、项目配置检查结论

| 检查点 | 结论 |
| --- | --- |
| 数据库配置来源 | 仅 [application.yml](../backend-java/src/main/resources/application.yml) 一处 `spring.datasource`，无 Java 配置类、无第二个数据源 |
| 环境变量支持 | 完整支持：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`、`SERVER_PORT` |
| 硬编码密码 | 未发现；密码默认值为空，真实值只从环境变量读取 |
| `/api/health/ready` 实现 | [HealthService](../backend-java/src/main/java/com/serviceflow/ai/health/service/HealthService.java) 用 `JdbcTemplate` 真实执行 `SELECT 1`；失败时 [HealthController](../backend-java/src/main/java/com/serviceflow/ai/health/controller/HealthController.java) 返回 HTTP 503，错误信息只含数据库根因、不含密码 |
| 数据库不可用时能否启动 | 可以：`initialization-fail-timeout=-1`，启动后由 ready 接口如实报告 DOWN |
| 启动方式 | `backend-java` 下 `.\mvnw.cmd spring-boot:run`，或 `java -jar target\serviceflow-backend-0.1.0.jar`（JAVA_HOME 指向 JDK 21） |
| 现有测试 | `HealthControllerTest` 2 个用例，均不依赖真实数据库 |
| 密钥忽略 | `deploy/.env`、依赖目录、构建产物均被 `.gitignore` 忽略 |

## 三、验收项与实际结果

| # | 验收项 | 结果 | 依据 |
| --- | --- | --- | --- |
| 1 | 目录与关键配置存在 | **通过** | `serviceflow-ai/{frontend,backend-java,ai-python,deploy,docs}`、`README.md`、`AGENTS.md`、`.gitignore` 全部复现 |
| 2 | Maven 构建成功 | **通过** | `.\mvnw.cmd -B clean package`（JAVA_HOME=D:\JDK21）→ `BUILD SUCCESS`，JAR 24,660,864 字节 |
| 3 | 自动化测试通过 | **通过** | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`（HealthControllerTest） |
| 4 | `GET /api/health` | **通过** | HTTP 200，`{"service":"serviceflow-ai-backend-java","status":"UP","version":"0.1.0"}`（SERVER_PORT=8081 实测） |
| 5 | `GET /api/health/ready` 数据库不可用态 | **通过** | 未设置 `MYSQL_PASSWORD` 时 HTTP 503，`status=DOWN`，message 为真实 `Access denied for user 'serviceflow'@'localhost' (using password: NO)`，未谎报成功 |
| 6 | `GET /api/health/ready` 数据库正常态 | **未验证** | 助手执行环境无数据库密码，且与用户本机 PowerShell 进程空间独立，无法继承其环境变量；需用户按第四节命令启动后实测 |
| 7 | Python 服务 | **通过** | `/health` 与 `/health/ready` 均 HTTP 200、`status=UP` |
| 8 | 前端页面与代理 | **通过** | `http://127.0.0.1:5173/` HTTP 200；验证时代理临时指向 8081，`/api/health` 经代理 HTTP 200（验证后已恢复为 8080） |
| 9 | 浏览器端到端 | **通过** | 浏览器子代理实测：页面标题与三张服务卡片渲染正常，点击“检查后端连接”显示成功面板（service=serviceflow-ai-backend-java、status=UP、version=0.1.0），无错误面板 |
| 10 | 日志不泄露密码 | **通过** | 本次未设置任何密码；启动日志（.tools/java-run4.log）只含常规 Spring/Hikari 输出，连接报错信息不含凭据 |
| 11 | Git 忽略规则 | **通过** | `git check-ignore` 实测命中：`deploy/.env`（规则 .env）、`node_modules/`、`backend-java/target/`、`ai-python/.venv/` |
| 12 | 首次 Git 提交 | **通过** | 复现完成后已 `git init` 并提交首个 commit（吸取教训：上次未提交导致工作区清理后源码全失） |

## 三之补充、8080 端口冲突说明

本机 8080 被用户在 IDEA 中调试的另一项目（sky-take-out，命令行含 `com.sky.SkyApplication`）占用。按项目约定不终止未知/他人进程，本次 Java 验证使用 `SERVER_PORT=8081`，前端代理临时改为 8081 完成链路验证后已恢复为 8080。**用户自行验证时**：若 8080 仍被占用，先结束 IDEA 调试或同样使用 8081（此时需同步临时改 `frontend/vite.config.ts` 代理目标）。

## 四、数据库连接配置方式（本机）

Java 后端通过环境变量读取连接参数，不读取 `deploy/.env`（该文件只供 Docker Compose 使用）：

| 环境变量 | 本项目使用值 |
| --- | --- |
| `MYSQL_HOST` | `127.0.0.1` |
| `MYSQL_PORT` | `3306` |
| `MYSQL_DATABASE` | `serviceflow` |
| `MYSQL_USER` | `serviceflow` |
| `MYSQL_PASSWORD` | 由开发者在本机自行设置，不进入仓库、聊天与日志 |

密码以不回显方式输入（只对当前 PowerShell 窗口有效）：

```powershell
$secure = Read-Host "请输入 serviceflow 用户的 MySQL 密码（输入时不显示）" -AsSecureString
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:MYSQL_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$env:MYSQL_HOST = "127.0.0.1"; $env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "serviceflow"; $env:MYSQL_USER = "serviceflow"
```

## 五、尚未解决 / 未验证项

1. **`/api/health/ready` 成功态（HTTP 200 + mysql UP）未验证** —— 唯一阻塞项。助手无法获取真实密码（对话中未出现、进程空间隔离），需用户在本机 PowerShell 执行下方命令后补验。
2. Docker Compose 未实测（本机无 Docker）。
3. 第一阶段验收条件中“MySQL 实际连接成功”未达成前，**第一阶段不算全部完成，不进入第二阶段工单 CRUD**。

## 六、用户需执行的最终验收命令（可复制）

前提：若 8080 仍被 IDEA 调试的 sky-take-out 占用，先停止该调试，或把下面 `SERVER_PORT` 改为 8081。

```powershell
Set-Location C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai\backend-java
$env:JAVA_HOME = "D:\JDK21"
# 停掉本项目旧 Java 进程（不影响其他程序）
Get-CimInstance Win32_Process -Filter "name='java.exe'" |
  Where-Object { $_.CommandLine -like '*serviceflow-backend*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
$secure = Read-Host "请输入 serviceflow 用户密码（不回显）" -AsSecureString
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:MYSQL_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$env:MYSQL_HOST = "127.0.0.1"; $env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "serviceflow"; $env:MYSQL_USER = "serviceflow"
& "D:\JDK21\bin\java.exe" -jar .\target\serviceflow-backend-0.1.0.jar
```

另开窗口验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/api/health/ready
```

期望：`ready` 返回 HTTP 200 且 `checks[0].status=UP`。把结果（只要状态码和 status 字段，不要发密码）告诉助手后，即可更新本文档并进入第二阶段。

## 七、本次复现与原始项目的差异

| 项 | 说明 |
| --- | --- |
| 源码 | 按历史验收记录与文件内容 1:1 复现，包结构、配置、接口行为一致 |
| 端口验证 | 因 8080 被占用改用 8081 验证（原始验证用 8080） |
| Git | 原始项目无提交；本次复现后立即首次提交 |
| 其余 | 无功能性差异 |

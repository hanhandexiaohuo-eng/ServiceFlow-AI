# 下次会话交接

最近更新：2026-11-14（工作区清理后按本文档复现项目）
详细验收记录见 [phase-1-acceptance.md](phase-1-acceptance.md)

## 项目目录

```
C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai
```

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3 + Vite + TypeScript（5173） |
| Java 后端 | Spring Boot 3.5.16 + Maven Wrapper（8080，编译目标 Java 17） |
| Python 服务 | FastAPI + Uvicorn（8000） |
| 数据库 | MySQL 8.4（3306，本机服务） |
| 后续 | DeepSeek API |

## 关键经验（2026-10-26 丢失教训）

**每次会话结束前必须 `git commit`。** 上次第一阶段全部完成后未提交，工作区清理导致全部源码丢失，只能按文档复现。恢复后第一件事：初始化提交。

## 本机环境注意

- 本机 8080 可能被其他 IDEA 调试项目占用（如 sky-take-out），占用时不要终止对方进程，改用 `$env:SERVER_PORT="8081"` 启动本项目，并同步修改 `frontend/vite.config.ts` 代理目标或按需验证。
- 本机 `npm.ps1` 入口损坏，一律用 `npm.cmd`。
- 运行 Java 优先显式使用 `D:\JDK21\bin\java.exe`，PATH 默认 java 可能是其他版本。

## 当前进度

（每次会话结束时更新）

- [x] 项目骨架复现（2026-11-14）
- [ ] 第一阶段验收：数据库连接成功态（`/api/health/ready` 返回 200 + mysql UP）
- [ ] 第二阶段：工单 CRUD
- [ ] 第三阶段：DeepSeek AI 能力

## 明天的第一步

1. 确认 `D:\JDK21\bin\java.exe` 存在，确认 8080 是否被占用（被占用则用 8081）。
2. 在**同一个** PowerShell 窗口设置五项 `MYSQL_*` 环境变量，密码用 `Read-Host -AsSecureString` 不回显输入：

```powershell
Set-Location C:\Users\zengyun\Documents\Trae-CN\temp_20261026_01\serviceflow-ai\backend-java
$env:JAVA_HOME = "D:\JDK21"
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

3. 另开窗口验证，把结果补进 `docs/phase-1-acceptance.md`：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/api/health/ready
```

期望：`/api/health` 为 `UP`；`/api/health/ready` 为 HTTP 200、`status=UP`。若仍是 503，读 `checks[0].message` 判断是密码错误、库不存在还是权限不足。

## 后续开发顺序

1. 完成 `ready` 成功态验收，**立即做首次 Git 提交**。
2. 设计工单表结构与迁移脚本（`deploy/init/` 下新增，不覆盖 `01-init.sql`）。
3. Java 工单 CRUD：DTO / service / controller + MockMvc 测试。
4. 前端工单列表与表单，走 `/api` 代理。
5. DeepSeek API 接入（密钥只走环境变量，由 Python 服务调用，Java 不直连大模型）。
6. 再考虑登录鉴权、Redis、消息队列。

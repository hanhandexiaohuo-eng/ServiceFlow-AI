# 第七阶段：Docker 容器化部署

> 本机当前**未安装 Docker**（`docker` 命令不存在），因此本次只交付可复现的部署文件与验证步骤，**未宣称已成功部署**。
> 已验证项：Compose 文件可被 YAML 解析（5 个服务、2 个持久卷）、后端镜像上下文只包含构建所需文件。
> 未验证项：镜像构建、容器启动、健康检查、数据持久化，均需在安装 Docker 的环境按本文步骤执行后回填结果。

## 1. 交付文件

| 文件 | 作用 |
| --- | --- |
| [docker-compose.yml](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/docker-compose.yml) | 统一编排 mysql / migrate / ai / backend / frontend |
| [backend-java/Dockerfile](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/backend-java/Dockerfile) | 多阶段构建：Maven 17 构建 → JRE 17 Alpine 运行，非 root |
| [ai-python/Dockerfile](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/ai-python/Dockerfile) | python:3.11-slim + 运行期依赖，uvicorn 单进程 |
| [frontend/Dockerfile](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/Dockerfile) | Node 20 构建（含 vue-tsc）→ nginx 托管 |
| [frontend/nginx.conf](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/frontend/nginx.conf) | SPA 回退、静态缓存、`/api` 反代后端、透传 `X-Request-Id` |
| 各目录 `.dockerignore` | 排除 target/node_modules/.venv/日志，缩小构建上下文 |
| [deploy/.env.example](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/deploy/.env.example) | 全部环境变量占位符 |
| [deploy/docker-compose.yml](file:///C:/Users/zengyun/Documents/Trae-CN/temp_20261026_01/serviceflow-ai/deploy/docker-compose.yml) | 保留的「只起 MySQL」本地开发编排 |

## 2. 服务拓扑

```
浏览器
  │ http://127.0.0.1:${FRONTEND_PORT:-8085}
  ▼
frontend (nginx:80)  ──/api──▶  backend (Java:8080)
                                   │            │
                                   ▼            ▼
                              mysql:3306    ai:8000 (Python)
```

- 只有 frontend 映射到宿主机（默认 8085，绑定 127.0.0.1）；MySQL、Java、Python 仅在内部网络 `serviceflow` 中可达。
- 前端与后端同源，因此默认不需要 CORS。

## 3. 环境变量与密钥

1. `cp deploy/.env.example .env`（在仓库根目录）。
2. 至少填写：`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`JWT_SECRET`（≥32 字节）、`DEEPSEEK_API_KEY`（不演示 AI 功能可留空）。
3. `.env` 已被 `.gitignore` 忽略；镜像构建过程不接收任何密钥，密钥只在运行时以环境变量注入。
4. 若密钥曾出现在聊天/提交/日志中，请先轮换，见 `docs/phase7-security.md` 第 4 节。

## 4. 常用命令

在仓库根目录执行：

```bash
# 构建并启动全部服务（后台）
docker compose --env-file .env up -d --build

# 查看状态与健康检查结果
docker compose ps

# 查看某服务日志（frontend/backend/ai/mysql/migrate）
docker compose logs -f backend

# 重启单个服务
docker compose restart backend

# 停止但保留数据卷
docker compose down

# 停止并删除数据卷（会清空数据库，谨慎）
docker compose down -v
```

## 5. 启动顺序与健康检查

| 服务 | 健康检查 | 说明 |
| --- | --- | --- |
| mysql | `mysqladmin ping` | 每 10s，最多 12 次，启动宽限 40s |
| migrate | 一次性任务，退出码 0 | 执行成功后 backend 才启动 |
| ai | `GET /health` | 只看进程存活；未配置 Key 时 AI 功能会在调用时返回错误，但容器仍可用 |
| backend | `GET /api/health/ready` | 会用真实 SQL 检查数据库，而不是只看端口 |
| frontend | `GET /healthz` | nginx 本地返回 200 |

依赖使用 `depends_on: condition: service_healthy / service_completed_successfully`，不是裸 `depends_on`。

## 6. 数据库初始化与迁移

- **新数据卷**：MySQL 首次启动自动执行 `deploy/init/01~05`，覆盖 phase1~4（建库、工单、用户、事件、调度表与种子）。
- **迁移任务**：`migrate` 容器在 MySQL 健康后执行 `phase5-ai-knowledge.sql` 与 `phase6-analytics-indexes.sql`；两个脚本均为可重复执行（`CREATE TABLE IF NOT EXISTS`、`INSERT IGNORE`、索引存在性判断），重复 `up` 不会产生重复数据。
- **已有数据库升级**：按文件名顺序手动执行 `deploy/migrations/*.sql`；`phase4` 含非幂等种子插入，只在旧库升级时执行一次。
- **持久化**：数据在命名卷 `mysql-data`，`docker compose down` 不会删除；`down -v` 才会清空。
- **备份/恢复**：

```bash
docker compose exec mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" serviceflow' > backup.sql
docker compose exec -T mysql sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" serviceflow' < backup.sql
```

## 7. 部署后验证清单（需在具备 Docker 的机器执行）

| # | 步骤 | 期望结果 |
| --- | --- | --- |
| 1 | `docker compose ps` | 5 个服务存在，mysql/ai/backend/frontend 为 `healthy`，migrate 为 `exited (0)` |
| 2 | `curl -s http://127.0.0.1:8085/healthz` | 返回 `ok` |
| 3 | `curl -s http://127.0.0.1:8085/api/health/ready` | `status=UP`，checks 含 mysql（M5 后含 ai） |
| 4 | 浏览器打开 `http://127.0.0.1:8085` | 出现登录页，静态资源无 404 |
| 5 | 登录管理员账号 | 返回 Token，进入数据驾驶舱 |
| 6 | 创建一张工单并查看详情 | 数据写入 MySQL，刷新后仍存在 |
| 7 | `docker compose restart` | 重启后工单数据仍存在（持久化验证） |
| 8 | `docker compose logs backend \| Select-String "X-Request-Id"` | 访问日志包含请求 ID（M5 后） |
| 9 | 未配置 `DEEPSEEK_API_KEY` 时点击 AI 分析 | 返回明确的 AI 配置错误，不影响其他功能 |
| 10 | 配置 Key 后再次分析 | 返回结构化结果，`ai` 日志有 `requestId` 与耗时 |

## 8. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `MYSQL_PASSWORD is required` | 未传 `.env` | 使用 `--env-file .env`，或确认根目录 `.env` 存在 |
| backend 一直 unhealthy | 数据库未就绪或密码错误 | `docker compose logs mysql` / `logs backend`，检查 `MYSQL_*` |
| frontend 502 | backend 未 healthy | 先看 `docker compose ps`，再查 backend 日志 |
| 8085 被占用 | 端口冲突 | `.env` 设置 `FRONTEND_PORT=8090` |
| 迁移失败 | 旧库已存在不兼容结构 | 按 `deploy/migrations` 头部说明逐个人工执行并记录 |
| AI 分析返回 503 | 未配置 `DEEPSEEK_API_KEY` | 在 `.env` 填入后 `docker compose up -d ai backend` |

## 9. 不用 Docker 的本地启动（对照）

```bash
# 1) MySQL
cd deploy && docker compose up -d        # 或使用本机 MySQL 8.4

# 2) Python AI
cd ai-python
.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 3) Java 后端（需设置 JAVA_HOME 指向 JDK 21）
cd backend-java
$env:MYSQL_PASSWORD='***'; .\mvnw.cmd spring-boot:run

# 4) 前端
cd frontend
node .\node_modules\vite\bin\vite.js
```

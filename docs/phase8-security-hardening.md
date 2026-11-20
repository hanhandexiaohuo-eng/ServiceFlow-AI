# Phase 8：生产化安全加固与管理能力补齐（P0 + P1 应用层）

> 时间：2026-11-18　环境：阿里云 ECS `120.26.150.85` `/opt/serviceflow-ai`（Docker Compose）
> 范围决策：HTTPS 暂缓（服务器仅 IP、无域名/证书），先完成应用层安全；账号立即轮换为强随机密码；
> AI 模型治理、用户管理与操作日志页面（第二阶段）本轮不实现。

## 1. 交付范围

| 类别 | 内容 | 状态 |
| --- | --- | --- |
| P0 凭据治理 | 清除前端/文档/脚本中的测试账号与默认密码；CI 密钥扫描阻断；管理员初始化走环境变量；首登强制改密并审计；凭据与会话轮换 | 完成 |
| P1 认证会话 | HttpOnly/SameSite Cookie、CSRF 双提交、会话版本强制失效、登录限流与锁定、防用户名枚举、退出即失效 | 完成 |
| P1 接口与运维 | 健康检查收紧（公网最小化、`/ready` 仅内网）、隐藏版本、安全响应头与 CSP、静态资源缓存、鉴权失败审计 | 完成 |
| P1 CI/测试 | CI 增加 secret-scan；后端 201 个测试、前端导航一致性测试 6 个 | 完成 |
| 未接入功能 | `/system/users`、`/system/logs` 菜单隐藏 + 功能开关 + 直链「功能未启用」页 | 完成 |
| HTTPS/HSTS | 需域名与证书，暂缓（配置位已预留） | 未做 |
| AI 模型治理 / 用户与审计页面 / 后端聚合与大数据量测试 | 超出本轮约定范围 | 未做 |

## 2. 关键设计与实现

### 2.1 会话与 CSRF

- 登录成功后仅通过 `Set-Cookie` 下发：
  - `sf_session`：`HttpOnly; SameSite=Lax; Path=/; Max-Age=<JWT TTL>`，`Secure` 由 `COOKIE_SECURE` 控制（HTTPS 环境必须为 `true`）。
  - `sf_csrf`：非 HttpOnly（供页面脚本读取后回填请求头），本身不是凭据。
- 响应体不再返回任何访问 Token；前端不再写 `localStorage`/`sessionStorage`。
- 写操作（POST/PUT/PATCH/DELETE）在 Cookie 认证下必须携带 `X-CSRF-Token`，服务端与 `sf_csrf` 做常量时间比较。
- `AuthInterceptor` 每请求查库：禁用即时生效、角色以数据库为准；JWT `ver` 声明与 `users.token_version` 不一致即视为会话已撤销。
- 退出登录递增 `token_version`，使该用户所有已签发 Token 立即失效。

### 2.2 首登强制改密

- `users.password_change_required=1` 时，仅放行 `/api/auth/password`、`/api/auth/logout`、`/api/auth/me`，其余接口返回 403「请先修改初始密码」。
- 前端拦截 403 文案跳转 `/change-password`；改密成功返回新会话 Cookie 并清除标记。
- 管理员初始密码只来自 `ADMIN_INITIAL_PASSWORD` 环境变量，创建时即标记必须改密。

### 2.3 登录防护

- 内存滑动窗口限流：同一 IP 10 分钟内失败 10 次 → 429（`Retry-After: 600`）。
- 数据库连续失败 5 次锁定 15 分钟；成功登录清零。
- 用户不存在、密码错误、已锁定统一返回「用户名或密码错误」，避免用户名枚举；限流/锁定/失败均写审计。

### 2.4 审计日志

- 新表 `audit_logs`：操作者、时间、动作、对象类型/标识、变更摘要、IP、请求追踪 ID、结果、失败原因。
- 应用层只提供写入，不提供编辑/删除接口。
- 数据库触发器 `trg_audit_logs_no_update` / `trg_audit_logs_no_delete` 强制 append-only（实测 `ERROR 1644`）。
- `detail` 截断 500 字符，禁止写入密码、Token、API Key、完整提示词/模型响应。

### 2.5 健康检查与响应头

- `/api/health` 公网仅返回 `{"status":"UP"}`；`/api/health/ready` 经 nginx 返回 403，仅供容器/内网。
- nginx：`server_tokens off`、隐藏 `X-Powered-By`/`Server`；后端 `server.server-header: ""`。
- 统一安全头：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、`Permissions-Policy`、CSP：
  - `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; ...; frame-ancestors 'none'`
  - `style-src 'unsafe-inline'` 的唯一原因：ECharts/Vue 运行时写元素内联样式；脚本侧不含 `unsafe-inline`，也未放开第三方来源。
- `/index.html` 不缓存；`/assets/` 30 天缓存。

### 2.6 功能开关与直链一致性

- `FEATURE_FLAGS`（`frontend/src/constants/features.ts`）默认关闭 `systemUsers`、`systemLogs`；侧边栏按开关过滤。
- 路由改为 `createWebHistory()`（nginx 已有 SPA 回退），直链 `/system/users`、`/system/logs` 渲染「功能未启用」说明页；旧 `#/` 链接在路由初始化时归一化为路径。

## 3. 变更文件清单（本轮新增/修改）

数据库与部署
- `deploy/migrations/20261118-security-hardening.sql`：users 安全列 + `audit_logs` 表（幂等）
- `deploy/migrations/20261118-audit-append-only.sql`：append-only 触发器（需 root/SUPER，单独执行）
- `deploy/init/06-audit-append-only.sql`：新库初始化时以 root 创建表与触发器
- `docker-compose.yml`：migrate 追加安全迁移；backend 增加 `COOKIE_SECURE`
- `deploy/.env.example`：新增 `COOKIE_SECURE`
- `backend-java/Dockerfile`：`-Dmaven.test.skip=true`（原参数仍编译测试导致构建失败）
- `backend-java/src/main/resources/application.yml`：关闭 server header、错误详情最小化、Cookie Secure 配置

后端（`backend-java/src/main/java/com/serviceflow/ai/`）
- `auth/web/AuthCookies.java`、`common/web/ClientIp.java`、`common/web/RequestIdFilter`（用户 MDC）
- `auth/security/PasswordPolicy.java`、`auth/service/LoginAttemptGuard.java`
- `common/audit/AuditLogService.java`
- `auth/controller/AuthController.java`、`auth/service/AuthService.java`、`auth/interceptor/AuthInterceptor.java`
- `auth/jwt/JwtService.java`（`ver` 声明）、`auth/repository/UserRepository.java`、`auth/entity/User.java`
- `common/exception/TooManyRequestsException.java`、`GlobalExceptionHandler.java`
- `health/controller/HealthController.java`

前端（`frontend/`）
- `src/api/request.ts`、`src/api/auth.ts`（Cookie 会话、CSRF、401/403 处理）
- `src/router/index.ts`（history 路由、旧 hash 兼容、改密守卫）、`src/views/ChangePasswordView.vue`
- `src/views/LoginView.vue`、`src/components/Topbar.vue`、`src/api/health.ts`、`src/components/ServiceHealth.vue`
- `src/constants/features.ts`、`src/constants/menu.ts`、`src/components/Sidebar.vue`、`src/views/ComingSoonView.vue`
- `src/views/SystemMonitorView.vue`
- `tests/navigation-consistency.test.mjs`、`package.json`（`npm test`）
- `nginx.conf`（安全头、CSP、隐藏版本、ready 403、缓存策略）

CI 与脚本
- `.github/workflows/ci.yml`：`secret-scan` 作业前置门禁，构建后再扫描产物；前端执行 `npm test`
- `scripts/scan_secrets.py`：零依赖密钥扫描（私钥、云密钥、`sk-*`、JWT、历史默认凭据、赋值式密钥）
- `.tools/phase4-smoke.ps1`：口令改为环境变量、Cookie + CSRF 会话

测试
- 更新 `AuthServiceTest`、`AuthControllerTest`、`AuthInterceptorTest`、`JwtServiceTest`、`HealthControllerTest`、`AiEndpointAuthTest`
- 新增 `PasswordPolicyTest`、`LoginAttemptGuardTest`

文档脱敏
- `docs/phase5-test-report.md`、`docs/phase6-test-report.md`、`docs/architecture-audit.md`、`docs/module-1-auth-design.md`、`docs/demo-guide.md`：移除默认口令字面量，异常场景改为会话失效/CSRF 失效

## 4. 部署步骤（已在生产执行）

1. 备份数据库：`mysqldump` 至 `/opt/serviceflow-backups`（保留 7 天）。
2. 更新 `.env`（不提交）：`JWT_SECRET`、`ADMIN_INITIAL_PASSWORD`、`COOKIE_SECURE=false`（HTTP 过渡期）。
3. 上传源码（`backend-java`、`frontend`、`deploy`、`docker-compose.yml`）。
4. 应用迁移：`docker compose up -d migrate`（应用账号可执行列/表变更；触发器需 root，见下）。
5. 已有库补触发器：
   ```bash
   docker exec -i serviceflow-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" serviceflow' \
     < deploy/migrations/20261118-audit-append-only.sql
   ```
6. 重建并启动：`docker compose up -d --build`，确认 4 个容器 healthy。

## 5. 回滚方案

- 镜像可回滚：`docker compose up -d` 使用上一次镜像标签（本轮未打 tag，重建旧提交即可）。
- 数据库为**纯增量**：新增 `users` 列、`audit_logs` 表与触发器，不改既有业务数据；回退应用无需回滚 DDL。
  如需彻底回退：`DROP TRIGGER` → `DROP TABLE audit_logs` → 删除 5 个新增列（谨慎，审计数据会丢失）。
- 会话回滚：旧版本 Token 存 localStorage，新版本 Cookie 与之互不影响；回退后用户需重新登录。
- 密码策略回滚无需数据修复：`password_change_required`、`token_version` 保留也无副作用。

## 6. 验证结果（真实执行）

| 项目 | 结果 |
| --- | --- |
| 后端构建与测试（`mvnw clean package`） | `Tests run: 201, Failures: 0, Errors: 0`，BUILD SUCCESS |
| 前端类型检查 / 构建 | `vue-tsc --noEmit` 通过；`vite build` 成功 |
| 前端导航一致性测试 | 6/6 通过（菜单唯一、路由存在、开关默认关闭、侧边栏过滤、history+SPA 回退） |
| 仓库密钥扫描 | `scripts/scan_secrets.py`：未发现凭据/Token/私钥 |
| 构建产物扫描 | 容器内 bundle 无历史默认口令、测试账号提示与 `sf_token` 字样 |
| 会话 Cookie | 登录 200，响应体无 Token；`sf_session` 含 `HttpOnly; SameSite=Lax`；`sf_csrf` 可读 |
| 首登强制改密 | 未改密访问 `/api/tickets` 403；改密后 200 |
| CSRF | 无 `X-CSRF-Token` 的 logout 403；携带后 200 |
| 登录防护 | 旧密码 401；同一来源 10 次失败后第 11 次 429 |
| 健康检查 | `/api/health` 仅 `{"status":"UP"}`；`/api/health/ready` 经 nginx 403 |
| 安全响应头 | 含 CSP / nosniff / X-Frame-Options / Referrer-Policy / Permissions-Policy；`Server: nginx`（无版本） |
| 审计 append-only | UPDATE/DELETE 均被阻断（`ERROR 1644`）；审计表无 password/token/secret 样式内容 |
| 功能未启用页 | 浏览器实测 `/system/users`、`/system/logs` 显示「功能未启用」；侧边栏无这两项 |
| 登录页 | 浏览器实测无测试账号/默认密码提示 |

## 7. 风险与兼容性

- **HTTPS 未启用**：当前 Cookie 无 `Secure`、无 HSTS，登录流量仍为明文。上线域名/证书后必须把 `COOKIE_SECURE=true`、补充 443 监听、HTTP→HTTPS 跳转与 HSTS。
- `Server: nginx` 仍会出现在响应头（无版本号），信息量低；如需完全隐藏需改 nginx 源码或置于云 WAF 后。
- CSP 允许 `style-src 'unsafe-inline'`（ECharts/Vue 内联样式），脚本侧未放开。
- history 路由替换 hash 路由：旧书签 `/#/path` 已做自动归一化；外部系统若硬编码 hash 链接需同步。
- 触发器依赖 root/SUPER：普通迁移无法创建，已拆分为受控步骤；误删触发器会失去数据库层 append-only 保护，需监控。
- 登录限流为单实例内存实现，多副本部署需替换为 Redis。
- 生产管理员/账号密码已轮换，旧会话通过 `token_version` 失效；首次登录仍需改密。

## 8. 未完成事项

1. HTTPS/443 证书、HTTP 跳转、HSTS（等待域名与证书）。
2. AI 模型管理：供应商/模型目录、场景路由、密钥只写不可读、服务端代理测试连接、脱敏、降级熔断、配额成本与 RBAC。
3. 用户管理、角色权限、登录记录与审计日志页面（第二阶段），以及审计导出/归档保留策略。
4. 后端聚合接口与大数据量仪表盘性能测试；RBAC 全量越权自动化测试。
5. 管理类写操作统一二次确认组件（本轮仅改密/退出等关键操作具备鉴权与审计）。

## 9. 交接记录（2026-11-18 暂停）

- 生产现状：`mysql / ai / backend / frontend` 四个容器 healthy；migrate 任务退出码 0；migrate 已含本轮安全迁移。
- 账号状态：`admin` 已完成首次改密（`password_change_required=0`，`token_version=4`）；`bob` 待首次改密（`=1`）。
- 数据库：新增列与 `audit_logs` 表为纯增量；append-only 触发器已生效。
- 备份：`/opt/serviceflow-backups/src-before-security-20261118-151925.tgz`、`db-pre-security-20261118-151925.sql.gz`。
- 凭据：仅存于运维本地临时文件与服务端 BCrypt 哈希，不进入仓库/文档。
- 恢复工作时建议顺序：① HTTPS 与 HSTS → ② AI 模型治理（密钥只写、服务端代理、脱敏与降级）→ ③ 用户/角色/审计页面 → ④ 后端聚合接口与 RBAC 越权、大数据量性能测试。

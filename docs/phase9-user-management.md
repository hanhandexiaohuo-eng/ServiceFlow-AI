# Phase 9：用户管理与操作日志（第二阶段）

> 时间：2026-11-19　环境：`/opt/serviceflow-ai`（Docker Compose）
> 前置：Phase 8 完成 P0/P1 应用层安全加固（Cookie 会话、CSRF、强制改密、审计表、功能开关）。

## 1. 交付内容

| 能力 | 说明 | 状态 |
| --- | --- | --- |
| 数据模型 | `users.department`；`audit_logs(result, created_at)` 索引 | 完成 |
| 用户管理 API | 列表/搜索/创建/启停用/角色/部门/密码重置，仅 ADMIN | 完成 |
| 操作日志 API | 只读检索（动作/操作者/结果/日期/分页），仅 ADMIN | 完成 |
| 前端页面 | `/system/users`、`/system/logs` 真实页面，菜单 `adminOnly` + 功能开关 | 完成 |
| 二次确认 | 停用/启用、角色调整、密码重置、创建均需弹窗确认 | 完成 |
| 审计 | 所有管理写操作写 `audit_logs`，摘要不含密码/Token | 完成 |
| 删除用户 | 不提供删除接口，停用保留工单/调度/审计关联 | 按约束设计 |
| 审计导出 | 未开放（需单独权限与脱敏评审） | 未做 |
| 独立登录记录表 | 复用 `audit_logs` 的 `LOGIN/AUTH_FAILURE` 记录 | 按需简化 |

## 2. 数据模型

`deploy/migrations/20261119-user-admin.sql`（可重复执行）：
- `users.department VARCHAR(64) NULL`：部门 / 团队归属。
- `audit_logs` 增加 `idx_audit_result(result, created_at)`，支撑结果 + 时间过滤。
- 不新增/删除业务表，不修改既有用户数据。

## 3. 后端设计

### 3.1 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/users?keyword=&role=&status=&page=&size=` | 分页列表（关键字匹配用户名/姓名） |
| POST | `/api/admin/users` | 创建，初始密码强制下次登录修改 |
| PUT | `/api/admin/users/{id}/profile` | 姓名、部门 |
| PATCH | `/api/admin/users/{id}/status` | 启用(1)/停用(0) |
| PUT | `/api/admin/users/{id}/role` | 角色 ADMIN/AGENT/USER |
| POST | `/api/admin/users/{id}/password-reset` | 设置临时密码，不返回密码 |
| GET | `/api/admin/audit-logs?action=&actor=&result=&from=&to=&page=&size=` | 只读审计检索 |

### 3.2 权限与保护

- 所有接口经 `AuthInterceptor`：登录态 + `token_version` + Cookie 写操作 CSRF。
- 业务层再次校验 `UserRole.ADMIN`，非管理员 403（生产实测 AGENT 访问返回 403）。
- 自我保护：不能停用自己、不能修改自己的角色、不能给自己重置密码。
- 最后管理员保护：不能停用或降级最后一个启用管理员。
- 角色变更、停用、密码重置都会提升 `token_version`，旧会话立即失效。
- 不提供删除接口，避免破坏工单、调度、审计的历史关联。

### 3.3 审计与脱敏

- 动作：`USER_CREATE`、`USER_UPDATE`、`USER_ENABLE`、`USER_DISABLE`、`USER_ROLE_CHANGE`、`USER_PASSWORD_RESET`。
- 记录字段：操作者、时间、动作、对象类型/ID、变更摘要、来源 IP、请求追踪 ID、结果。
- 摘要只包含角色、部门、状态等非敏感信息；密码与 Token 不进入审计（单测断言摘要不含明文密码）。
- 审计表仍由触发器强制 append-only，无编辑/删除接口。

### 3.4 来源 IP 修正

nginx 由 `$proxy_add_x_forwarded_for` 改为覆盖 `$remote_addr`，避免客户端伪造 `X-Forwarded-For` 污染审计与限流来源；实测伪造 `1.2.3.4` 后审计仍记录真实来源。

## 4. 前端设计

- `SystemUsersView.vue`：服务端分页、关键字/角色/状态筛选，创建/编辑/角色/重置密码弹窗，停用启用二次确认；无密码列。
- `SystemLogsView.vue`：动作/操作者/结果/日期筛选，服务端分页，只读展示。
- 菜单新增 `adminOnly`，非管理员不渲染；功能开关 `systemUsers`/`systemLogs` 开启。
- 非管理员直链访问时页面展示「仅管理员可访问」，不请求数据。
- 导航一致性测试改为断言：两个菜单不再是 `soon`、标记 `adminOnly`、路由存在、开关开启。

## 5. 变更文件

后端
- `auth/dto/AdminUserResponse.java`、`AdminUserCreateRequest.java`、`UserProfileUpdateRequest.java`、`UserStatusUpdateRequest.java`、`UserRoleUpdateRequest.java`、`UserPasswordResetRequest.java`
- `auth/service/AdminUserService.java`、`auth/controller/AdminUserController.java`
- `common/audit/AuditLogResponse.java`、`AuditLogQueryService.java`、`AuditLogController.java`
- `auth/entity/User.java`、`auth/repository/UserRepository.java`
- 测试：`AdminUserServiceTest.java`

前端
- `src/api/admin.ts`、`src/views/SystemUsersView.vue`、`src/views/SystemLogsView.vue`
- `src/constants/features.ts`、`src/constants/menu.ts`、`src/components/Sidebar.vue`、`src/router/index.ts`
- `tests/navigation-consistency.test.mjs`、`nginx.conf`

部署
- `deploy/migrations/20261119-user-admin.sql`、`docker-compose.yml`（migrate 追加）

## 6. 验证结果（真实执行）

| 项目 | 结果 |
| --- | --- |
| 后端测试 | `Tests run: 208, Failures: 0, Errors: 0` |
| 前端类型检查 / 构建 | `vue-tsc --noEmit` 通过；`vite build` 成功 |
| 导航一致性测试 | 6/6 通过 |
| 迁移 | `serviceflow-migrate` 退出码 0 |
| 管理员接口 | 登录 200；`/api/admin/users` 200；`/api/admin/audit-logs` 200 |
| RBAC 越权 | bob（AGENT）访问两个管理接口均 403，`/api/tickets` 200 |
| 前端页面 | 浏览器实测：侧边栏出现两项；用户列表含 admin/bob；日志 44 条；无「功能未启用」 |
| 审计 append-only | UPDATE/DELETE 仍被触发器阻断（Phase 8 已验证并保持） |
| 来源 IP | 伪造 XFF 被忽略，记录真实来源 |

## 7. 部署步骤（已执行）

1. 备份数据库（沿用每日 3:00 备份）。
2. 上传 `docker-compose.yml`、`backend-java/src`、`frontend`、`deploy/migrations`、`deploy/init`。
3. `docker compose up -d migrate`（应用 `20261119-user-admin.sql`）。
4. `docker compose up -d --build backend frontend`。
5. 验证：管理员登录 → 两个页面可访问；AGENT 访问返回 403。

## 8. 回滚方案

- 应用回滚：重新构建上一版本镜像即可；接口新增不破坏旧前端。
- 数据库回滚（谨慎）：
  ```sql
  ALTER TABLE users DROP COLUMN department;
  ALTER TABLE audit_logs DROP INDEX idx_audit_result;
  ```
  回滚前应先确认无页面依赖部门字段；审计数据不受影响。
- 功能开关回滚：将 `features.ts` 中 `systemUsers`、`systemLogs` 置为 `false` 并重新构建前端，菜单立即隐藏、直链回到「功能未启用」。

## 9. 风险与未完成

- 「超级管理员」当前由 `ADMIN` 承担；若后续引入多级管理员，需要独立角色与权限表。
- 审计导出、保留周期与归档未开放；当前仅只读分页查询。
- 登录记录复用 `audit_logs`，如需独立登录报表可后续拆表。
- 部门为自由文本，尚无组织架构树与部门级数据权限。
- 管理端写操作依赖 CSRF + 二次确认，尚未做操作频率限制（登录已有）。

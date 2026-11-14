# 模块 1 验收记录：auth+user 认证模块

日期：2026-11-14
环境：本机 Windows + MySQL 8.4（真实库 serviceflow）+ Java 21.0.11 运行 jar + Vite dev server + 真实浏览器自动化。
验收方式：全部为真实执行的 HTTP 请求 / SQL 查询 / 浏览器操作，无任何"声明式通过"。

## 1. 单元测试（mvnw test，真实执行）

| 测试类 | 数量 | 结果 |
| --- | --- | --- |
| JwtServiceTest | 7 | 全绿（签发解析还原、过期拒绝、篡改签名拒绝、非法 Token 拒绝、异密钥拒验、空/短密钥 fail fast） |
| AuthServiceTest | 10 | 全绿（用户不存在、密码错误且提示一致、登录成功、禁用 401、超长密码按不匹配处理、种子创建/表非空跳过/无密码跳过/弱密码跳过/并发重复键吞掉） |
| AuthInterceptorTest | 8 | 全绿（无 Token、错误 Token、有效 Token 写上下文、禁用 401、已删用户 401、角色以 DB 为准、OPTIONS 放行、afterCompletion 清理） |
| AuthControllerTest | 4 | 全绿（登录成功结构、空参 400、错误密码 401、/me 返回当前用户且不含 password 字段） |
| TicketServiceTest | 4 | 全绿（creator 取自上下文显示名、普通用户删除 403、管理员删除成功、无上下文创建报编程错误） |
| TicketControllerTest（适配后） | 9 | 全绿 |
| HealthControllerTest | 2 | 全绿 |
| **合计** | **44** | **44 通过 / 0 失败 / 0 跳过** |

前端：`vue-tsc --noEmit && vite build` 通过。

## 2. 真实库与真实服务验收（15 步清单）

| # | 步骤 | 实测结果 |
| --- | --- | --- |
| 1 | 执行 `03-users.sql` | 成功；`users` 表 8 列结构与设计一致；`tickets` 表未改动 |
| 2 | 配置环境变量 | `MYSQL_PASSWORD` / `JWT_SECRET`（48 字节随机 base64）/ `ADMIN_INITIAL_PASSWORD` 均走进程环境变量 |
| 3 | 启动 Java 后端 | jar 启动成功（pid 20536，端口 8080） |
| 4 | 健康检查 | `GET /api/health/ready` → 200，`mysql: UP` |
| 5 | 初始化管理员 | 启动日志「初始管理员已创建，用户名 admin」；库中 `admin / ADMIN / status=1 / 哈希前缀 $2a$10$、长度 60` |
| 6 | 管理员登录 | `POST /api/auth/login` → 200，`code=200`，返回 token + user；错误密码 → **401** |
| 7 | 获取 JWT 访问 /me | `GET /api/auth/me` 带 Token → 200，username=admin、name=系统管理员；无 Token → **401** |
| 8 | 无 Token 创建工单 | `POST /api/tickets` 无 Token → **401**；篡改 Token（尾部加字符）→ **401** |
| 9 | 有 Token 创建工单 | admin 带 Token → HTTP **201**，ticketNo=SF20261114-RRCC |
| 10 | 普通用户删除工单 | bob（USER）删除 → **403**「仅管理员可删除工单」 |
| 11 | 管理员删除工单 | admin 删除 → **200** |
| 12 | creator 来源校验 | bob 创建时在请求体塞 `creator:"伪造者"`，落库为 **小明**（当前用户显示名），伪造字段被忽略 |
| 13 | 日志敏感信息检查 | 扫描 `logs/serviceflow-backend.log`：明文密码、BCrypt 哈希（`$2a$`）、`Bearer`、密钥变量名命中均为 **0 行**；登录日志只含 username |
| 14 | 保留原有功能 | 列表分页 200、修改 200（PUT 全量更新）、筛选正常 |
| 15 | 前端浏览器操作 | 真实浏览器自动化全部通过，见下 |

## 3. 前端浏览器验收（browser 自动化实测）

| 场景 | 实测结果 |
| --- | --- |
| 未登录访问 `#/tickets` | 自动跳转 `#/login`（路由守卫生效） |
| 错误密码登录 | 页面显示「用户名或密码错误」 |
| admin 登录成功 | 跳转首页，导航显示「系统管理员（ADMIN）」+ 退出登录按钮 |
| Token 自动携带 | 工单页列表请求自动带 `Authorization: Bearer`，列表加载成功；localStorage 存在 `sf_token`/`sf_user` |
| 角色显隐 | 删除按钮 `v-if="isAdmin"`，admin 可见；普通用户不可见（后端 403 兜底，前端只是体验优化） |
| 创建人输入框 | 已从新建表单移除，列表正常展示 creator 列 |

## 4. 数据库变更

- 新增 `users` 表（id / username 唯一 / password BCrypt 哈希 / name / role / status / created_at / updated_at）。
- 种子数据：`admin`（ADMIN，启动时按环境变量创建）、`bob`（USER，验收用，SQL 手工插入，密码哈希非明文）。
- `tickets` 表结构零改动，历史数据未受影响。

## 5. 遗留问题（如实记录）

1. 初始密码强度下限按用户要求放宽为 6 位（本地开发）；生产部署应使用 ≥12 位高熵密码。
2. 无用户管理界面：新增/禁用用户目前只能走 SQL（设计内取舍，后续模块再做管理页）。
3. Token 无黑名单机制：2 小时过期前的吊销（如修改密码后踢下线）暂不支持，禁用账号因每请求查库可立即生效。
4. 浏览器自动化中截图工具不可用，证据以 DOM 快照与网络日志为准。
5. PowerShell 变量展开曾导致手工 INSERT 的哈希被截断（`$2a` 被当变量），已修复并复验；教训：含 `$` 的 SQL 值要走文件 + source 或反引号转义。

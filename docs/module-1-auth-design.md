# 模块 1 设计文档：auth+user 认证模块

版本：v1.0（2026-11-14）
状态：**待用户确认后实施**
前置：第一阶段验收已通过；第二阶段工单 CRUD 真实库验收已通过；架构审计见 [architecture-audit.md](architecture-audit.md)。

---

## 1. 业务目的

解决当前唯一的 P0 问题：**所有工单接口零认证**，任何人可创建/修改/删除工单。

同时让"谁创建、谁处理"可追溯：`creator` 不再由请求方随意填写，而是来自登录身份。这为后续模块打地基：

- 工作流操作日志需要"操作人"
- 智能调度需要"处理人池"（AGENT 角色用户）
- 统计分析需要按人聚合

## 2. 范围控制（刻意不做）

| 不做 | 原因 |
| --- | --- |
| 注册页面、验证码、忘记密码 | 内部工单系统，用户由管理员创建 |
| RBAC 权限表（role/permission/menu 三表） | 当前只有 3 种角色、规则简单，过度设计 |
| 完整 Spring Security | 重量级，学习曲线陡；本模块只需 BCrypt + 拦截器 |
| Refresh Token、单点登录 | 单体应用 2 小时过期足够 |
| Redis 存 Token 黑名单 | 等"登录失效"成为真实需求再加（见 P2-2） |

第一版权限规则从简：

| 操作 | 规则 |
| --- | --- |
| 登录 | 公开 |
| 健康检查 | 公开（监控系统需要） |
| 工单查询/创建/更新 | 登录即可 |
| 工单删除 | 仅 ADMIN |
| 用户管理（创建/禁用） | 仅 ADMIN（本模块先不做管理界面，用 SQL/初始种子） |

## 3. 技术选型与理由

| 技术 | 版本 | 为什么选它 | 为什么不选替代品 |
| --- | --- | --- | --- |
| jjwt | 0.12.6 | JWT 事实标准库，Boot 3 兼容（F-Qing 同款） | java-jwt 略小众；自造 Token 无标准 |
| spring-security-crypto | 随 Boot 管理 | 只为 BCryptPasswordEncoder，单依赖无全家桶 | 整个 spring-boot-starter-security 会默认拦截一切，引入复杂度 |
| HandlerInterceptor | Spring 内置 | 仿苍穹外卖，轻量直观 | Servlet Filter 更底层但拿不到 Handler 信息 |
| ThreadLocal 上下文 | JDK | 仿苍穹外卖 BaseContext | 请求作用域 Bean 需额外配置 |

**对苍穹外卖方案的三个修正**（审计报告 6.6 节）：

1. MD5 → **BCrypt**：自带随机盐、慢哈希抗撞库，苍穹外卖的 MD5 已被攻破
2. ThreadLocal 在 `afterCompletion` 中 **remove**：苍穹外卖未清理，Tomcat 线程池复用会串号
3. 无硬编码默认密码：苍穹外卖 `123456` 硬编码在 SQL 里，我们走环境变量

## 4. 数据库设计（`deploy/init/03-users.sql`）

```sql
CREATE TABLE IF NOT EXISTS users (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(32)  NOT NULL UNIQUE COMMENT '登录名',
  password    VARCHAR(72)  NOT NULL COMMENT 'BCrypt 哈希，任何接口都不得返回',
  name        VARCHAR(64)  NOT NULL COMMENT '显示名',
  role        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/AGENT/USER',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

说明：`password` 长度 72 是 BCrypt 输出上限；`role` 用 varchar 存枚举名，与 tickets 表风格一致。

### 初始管理员种子（不学硬编码）

启动时检查两个条件，同时满足才创建 `admin`：

1. `users` 表为空
2. 环境变量 `ADMIN_INITIAL_PASSWORD` 已设置（非空）

创建时 BCrypt 加密入库，日志只输出"初始管理员已创建，用户名 admin"，**不输出密码**。任一条件不满足则静默跳过。

## 5. 后端结构

```
com.serviceflow.ai.auth/
├── controller/
│   └── AuthController          POST /api/auth/login（公开）、GET /api/auth/me（需登录）
├── service/
│   └── AuthService             校验账密（BCrypt.matches）、签发 Token、初始种子逻辑
├── repository/
│   └── UserRepository          users 表查询（JdbcTemplate，沿用现有风格）
├── entity/
│   ├── User
│   └── enums/UserRole          ADMIN / AGENT / USER
├── dto/
│   ├── LoginRequest            username + password（@NotBlank）
│   ├── LoginResponse           token + 用户信息
│   └── UserResponse            不含 password 的用户视图
├── jwt/
│   └── JwtService              签发（sub=userId，claim=role，2小时过期）与解析
├── interceptor/
│   └── AuthInterceptor         preHandle 校验 Token → UserContext.set；afterCompletion → remove
└── context/
    └── UserContext             ThreadLocal<LoginUser>（LoginUser = id/username/name/role）
```

拦截器注册（新增 `common/config/WebMvcConfig`）：

| 路径 | 规则 |
| --- | --- |
| `/api/auth/login` | 公开 |
| `/api/health/**` | 公开 |
| 其余 `/api/**` | 需要有效 Token，否则 401 `{code:401, message:"未登录或登录已过期"}` |

JWT 密钥：环境变量 `JWT_SECRET`（≥32 字符）。**未设置时启动直接报错**（fail fast），比静默使用弱默认值安全。

## 6. 工单模块集成点（最小改动）

| 改动 | 说明 |
| --- | --- |
| `TicketCreateRequest` 移除 `creator` 字段 | Service 从 `UserContext` 取当前用户名，前端表单同步去掉该输入框 |
| 删除接口加角色检查 | `UserContext` 中 role != ADMIN → 403 `{code:403, message:"仅管理员可删除工单"}` |
| 现有测试调整 | creator 来源变化；standalone MockMvc 不注册拦截器，认证逻辑单独测 |

## 7. 前端改动

1. 新增登录页：账密表单 → 成功存 token 到 `localStorage` → 跳转工单页
2. `api/request.ts` 请求封装：自动带 `Authorization: Bearer <token>`；收到 401 清 token 跳登录页
3. 创建工单表单移除"创建人"输入框，列表该列正常展示（来自后端）

## 8. 完整请求链路（登录后创建工单）

```
[浏览器] 提交账密
  → POST /api/auth/login → AuthController → AuthService
  → UserRepository 查用户 → BCrypt.matches(输入, 哈希) → JwtService 签发
  ← {code:0, data:{token, user}} 存 localStorage

[浏览器] 创建工单（fetch 封装自动带 Authorization: Bearer xxx）
  → AuthInterceptor.preHandle：解析 JWT → 校验签名/过期 → UserContext.set(loginUser)
  → TicketController（@Valid 校验）
  → TicketService：creator = UserContext.get().getName() → 编号生成 → 落库
  ← 201 {code:0, data:{工单}}
  → AuthInterceptor.afterCompletion：UserContext.remove()  ← 防串号，必须做
```

## 9. 异常与安全规范

| 场景 | 响应 |
| --- | --- |
| 用户不存在 / 密码错误 | 401 `{code:401, message:"用户名或密码错误"}`（不区分哪种，防用户名枚举） |
| 账号禁用 | 401 `{code:401, message:"账号已被禁用"}` |
| Token 缺失/过期/篡改 | 401 `{code:401, message:"未登录或登录已过期"}` |
| 非管理员删除 | 403 `{code:403, message:"仅管理员可删除工单"}` |

- 任何接口响应、任何日志都不得出现 password 或其哈希
- 登录失败不记录密码，只记录用户名
- JWT payload 不放敏感信息（只放 id/username/role/过期时间）

## 10. 测试计划

| 测试 | 内容 |
| --- | --- |
| JwtServiceTest | 签发后解析还原；过期 Token 拒绝；篡改签名拒绝 |
| AuthServiceTest | 密码错误 401；禁用账号 401；正常登录返回 Token；种子逻辑（表空+有密码才创建） |
| AuthInterceptorTest | 无 Token 401；有效 Token 放行且上下文有值；afterCompletion 清理 |
| AuthControllerTest | 参数缺失 400；登录成功响应结构 |
| 既有测试 | 全部适配 creator 来源变化，保持全绿 |

真机验证清单：

1. 无 Token POST /api/tickets → 401
2. 错误密码登录 → 401
3. 正确登录 → 200 + token
4. 带 Token 创建工单 → 201 且 creator = 登录用户名（非请求传入）
5. 普通用户删除工单 → 403
6. admin 删除 → 成功
7. 前端：未登录访问工单页 → 跳登录页；登录后正常操作

## 11. 实施顺序与提交计划

| 提交 | 内容 |
| --- | --- |
| commit 1 | `03-users.sql` + User 实体/枚举 + UserRepository + BCrypt 依赖 |
| commit 2 | JwtService + AuthService + AuthController + 种子逻辑 + 单元测试 |
| commit 3 | AuthInterceptor + UserContext + WebMvcConfig + 工单模块集成（creator/删除权限）+ 测试适配 |
| commit 4 | 前端登录页 + 请求封装 + 表单调整 |
| commit 5 | 文档（README、本设计文档标记已实施、验收结果） |

每个 commit 独立可回滚。实施中不改动既有已通过的行为，除非上表明确列出。

## 12. 验收标准（完成定义）

- [ ] 所有新测试 + 既有测试全绿
- [ ] 真机验证清单 7 项全部通过（真实 MySQL）
- [ ] 响应与日志中无密码/哈希
- [ ] `JWT_SECRET` 未设置时启动 fail fast
- [ ] 文档更新完毕并提交

## 13. 常见面试题（学完本模块应能回答）

1. JWT 由哪三部分组成？为什么最后一部分不能伪造？
2. 为什么密码要用 BCrypt 而不是 MD5/SHA256？"盐"解决了什么问题？
3. ThreadLocal 在线程池环境下为什么要 remove？不删会发生什么？
4. 拦截器和过滤器的区别？为什么权限校验用拦截器更方便？
5. 为什么"用户不存在"和"密码错误"要返回同样的提示？
6. Token 过期了但用户还在操作怎么办？（Refresh Token 的思路）

## 14. 自主练习任务

1. 给登录接口加"连续失败 5 次锁定 10 分钟"（提示：内存 Map 计数即可，不用 Redis）
2. 给 `GET /api/auth/me` 返回体加上"距离 Token 过期还剩多少秒"
3. 用 Postman/curl 手动篡改 Token 的一个字符，观察 401
4. 思考：如果要把"记住我 7 天"做进去，设计要怎么改？

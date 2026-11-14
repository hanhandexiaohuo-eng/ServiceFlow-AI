# ServiceFlow AI 架构审计报告

审计日期：2026-11-14
审计范围：backend-java 全部源码、frontend 全部源码、ai-python、deploy、工程化配置
参考项目：`Zhong-YC/sky-take-out`（master，Spring Boot 2.7.3）、`F-Qing/sky-take-out`（main，Spring Boot 3.5.16 + Spring AI 1.1.8）——均已实际阅读源码，关键文件见第六节。

---

## 1. 当前系统架构

单 Maven 模块 Spring Boot 应用 + Vue 3 单页应用 + FastAPI 空壳服务 + MySQL：

```
浏览器(Vue 5173) ──/api──> Vite proxy ──> Java(8080) ──JdbcTemplate──> MySQL(3306)
                                        └── (未来 REST) ──> FastAPI(8000) ──> DeepSeek
```

## 2. 当前目录结构（backend-java）

```
com.serviceflow.ai
├── ServiceFlowAiApplication
├── common.dto       ApiResponse / PageResponse
├── common.exception NotFoundException / GlobalExceptionHandler
├── health           controller / service / dto（健康检查）
└── ticket           entity / enums / dto / repository / service / controller
```

前端：`src/api/{health,tickets}.ts`、`src/components/TicketManager.vue`、`App.vue`（页签切换）。

## 3. 技术栈分析

| 项 | 现状 | 评价 |
| --- | --- | --- |
| Spring Boot | 3.5.16，Java 编译目标 17（运行于 JDK 21.0.11） | 正常；编译目标可升 21（P3） |
| 持久层 | spring-boot-jdbc（JdbcTemplate） | 当前 SQL 简单，够用；复杂查询增多后建议 MyBatis（见 6.9） |
| 校验 | spring-boot-starter-validation | 优于苍穹外卖（其靠手工 if 判断） |
| 构建 | Maven Wrapper 3.9.9 | 正常 |
| 测试 | JUnit 5 + MockMvc standalone + Mockito，11 用例 | 只覆盖 Controller 层（P2） |
| 前端 | Vue 3.5 + Vite 7 + TS，无路由/状态库 | 页面少时合理；页面增多需 vue-router（P2） |

## 4. 各模块职责

- `common`：统一响应 `{code,message,data}`（code 与 HTTP 状态码一致）、全局异常 → 400/404/500。
- `health`：liveness + readiness（真实 `SELECT 1`）。
- `ticket`：工单 CRUD，编号生成（SF+日期+4位随机），枚举校验，`@Transactional`。

## 5. 当前实现情况

已验证（真实执行）：构建通过、11/11 测试、参数校验 400、统一异常、健康检查、前端页面与代理、浏览器端到端。
未验证：真实数据库读写（`MYSQL_PASSWORD` 未配置到启动进程，见 phase-1-acceptance.md）。

## 6. 与苍穹外卖逐项对照（含实际源码依据）

### 6.1 项目结构

- **sky**：Maven 多模块 `sky-common`（result/context/properties/utils）+ `sky-pojo`（dto/entity/vo）+ `sky-server`（业务）。
- **本项目**：单模块，包内分层。
- **结论**：本项目规模远小于外卖系统，**不拆多模块**，保持"单模块 + 严格包边界"。避免为形式拆出三个几乎为空的模块。

### 6.2 分层架构

- **sky**：Controller → Service 接口 + ServiceImpl → Mapper（MyBatis）。
- **本项目**：Controller → Service 具体类 → Repository（JdbcTemplate），构造器注入。
- **结论**：层次等价。sky 源码用 `@Autowired` 字段注入（反面教材），我们已用构造器注入，**保持**。Service 是否抽接口：当前只有一个实现，**不抽**，避免无意义接口。

### 6.3 DTO / Entity / VO

- **sky**：`sky-pojo` 下 dto（入参）/ entity / vo（出参）三分。
- **本项目**：`TicketCreateRequest / TicketUpdateRequest`（入）+ `TicketResponse`（出）+ `Ticket`（实体）。
- **结论**：职责等价，命名更直白，**保持**。

### 6.4 统一响应

- **sky**：`Result{code:1成功/0失败, msg, data}`，**所有响应 HTTP 200**，业务错误靠 code 判断。
- **本项目**：`ApiResponse{code,message,data}`，code 与真实 HTTP 状态码一致（201/400/404/500）。
- **结论**：REST 语义上本项目更规范（202 面试官视角加分项），**保持本项目方案**。

### 6.5 统一异常

- **sky**：`GlobalExceptionHandler` 处理 `BaseException` + `SQLIntegrityConstraintViolationException`，**每个分支都有 `log.error`**。
- **本项目**：[GlobalExceptionHandler](../backend-java/src/main/java/com/serviceflow/ai/common/exception/GlobalExceptionHandler.java) 处理校验/非法参数/404/兜底，**但兜底分支没有记录日志**——500 发生时服务端无痕迹。
- **结论**：**需要补齐异常日志**（见问题 P1-3）。sky 把 SQL 约束报错文本拼给前端（`split[2]+"已存在"`）有信息泄露味，我们不学。

### 6.6 认证授权

- **sky**：jjwt 签发/解析 Token；`JwtTokenAdminInterceptor.preHandle` 校验失败直接 `response.setStatus(401)`；`BaseContext`（ThreadLocal）保存当前员工 id，供 AOP 填充 create_user/update_user。
- **本项目**：**完全没有认证**，`POST/PUT/DELETE /api/tickets` 任何人可匿名调用。
- **结论**：**P0，必须补**。借鉴 sky 的 JWT + 拦截器 + ThreadLocal 方案，但有针对性修正：
  1. sky 用 **MD5** 存密码（`DigestUtils.md5DigestAsHex`）——已不安全，我们改用 **BCrypt**（`spring-security-crypto`，不引入整个 Spring Security）。
  2. sky 拦截器设置了 `BaseContext.setCurrentId` 但**从不清除** ThreadLocal（线程池复用会串号）——我们在 `afterCompletion` 里 `remove()`。
  3. sky 有硬编码默认密码 `123456`——我们不设默认密码，首用户由迁移 SQL 指定。

### 6.7 事务管理

- **sky**：多表写（套餐+菜品）用 `@Transactional`，单表写不加。
- **本项目**：所有写方法加了 `@Transactional`，当前只有单表单写。
- **结论**：无冲突。工单状态机落地后会出现"更新状态 + 写操作日志"多写场景，事务边界放 Service 层，保持。

### 6.8 缓存设计

- **sky**：Redis 存店铺状态、菜品缓存——解决高频读。
- **本项目**：无高频读场景。
- **结论**：**暂不引入 Redis**。触发条件：统计分析接口被高频访问、或登录态需要集中管理时（见第九节评估表）。

### 6.9 数据访问

- **sky**：MyBatis + PageHelper（`PageHelper.startPage` 后查询自动分页），XML/注解 SQL。
- **本项目**：JdbcTemplate 手写 `LIMIT ? OFFSET ?` + `COUNT`。
- **结论**：当前 SQL 只有 5 条且简单，JdbcTemplate 无痛点。但工单工作流、操作日志、统计分析引入后，动态条件查询和联表查询会明显增多，**建议在第二阶段重构时将持久层迁移到 MyBatis**（国内企业主流、面试高频、XML 便于复杂 SQL 审查），迁移范围仅限 Repository 层，Service 不动。

### 6.10 状态流转

- **sky**：订单状态常量 + 明确动作（接单/拒单/派送/完成/取消），非法操作抛业务异常；`OrderTask` 定时处理超时单。
- **本项目**：[TicketService.update](../backend-java/src/main/java/com/serviceflow/ai/ticket/service/TicketService.java) 是全量 PUT，**任意状态可直接跳到任意状态**，无转换规则、无操作记录。
- **结论**：**P1，必须做状态机**（见目标架构 workflow）。

### 6.11 定时任务

- **sky**：`@Scheduled` + `@EnableScheduling`，按"状态 + 时间"批量捞单处理。
- **本项目**：无。
- **结论**：SLA 超时提醒/自动升级需要，模式照抄（幂等：按状态条件更新，天然可重入）。注意 sky `OrderTask` 的 cron `0 * * * * ?` 实为**每分钟**执行而注释写"每小时"——参考时要校验，不照抄。

### 6.12 消息通信

- **sky**：`WebSocketServer` 管理 session 池，下单/催单时 `sendToAllClient` 推送 JSON。
- **本项目**：无。
- **结论**：工单"被分配/状态变更"通知用 **SSE 优先**（单向推送、实现简单、浏览器原生支持），需要双向再升级 WebSocket。

### 6.13 统计报表

- **sky**：`ReportController` + 时间区间聚合 SQL + POI 导出 Excel。
- **本项目**：无。
- **结论**：做聚合 SQL + 前端 ECharts；Excel 导出 P3 再说。

### 6.14 AI 能力

- **F-Qing 改造版**：Spring Boot 3.5.16（与我们一致）+ `spring-ai-bom 1.1.8`，走 **OpenAI 兼容接口**（可直接指 DeepSeek `base-url`）。
- **本项目**：规划是 Java → FastAPI → DeepSeek（AGENTS.md 已定 Python=AI 能力边界）。
- **结论**：两条路线都成立。**建议保留 Java→FastAPI→DeepSeek**（符合已立架构、Python 服务不再空转、AI 依赖不侵入 Java 构建）；Java 侧用 `RestClient` + 超时 + 失败降级。API Key 走环境变量。

### 6.15 接口文档

- **sky**：knife4j（Swagger）。
- **本项目**：无，接口靠 README 表。
- **结论**：引入 `springdoc-openapi-starter-webmvc-ui`（Boot 3 原生支持，比 knife4j 轻），P2。

### 6.16 测试规范

- **sky**：基本无测试。
- **本项目**：11 个 Controller 层测试。
- **结论**：我们已优于参考项目；需要补 Service 层测试（状态机规则）与 Repository 层测试（P2）。

---

## 7. 发现的问题（分级）

### P0 — 安全 / 数据一致性

| # | 问题 | 位置 | 原因与风险 |
| --- | --- | --- | --- |
| P0-1 | 所有写接口无认证，任何人可创建/修改/删除工单 | `ticket/controller/TicketController` 全部方法 | 认证模块尚未开发；上线即裸奔 |
| P0-2 | 数据库成功态未验收 | 环境（非代码） | `MYSQL_PASSWORD` 未传入启动进程；阻塞后续所有真实库验证 |

### P1 — 核心架构与业务逻辑缺陷

| # | 问题 | 位置 | 原因与风险 |
| --- | --- | --- | --- |
| P1-1 | 工单状态无转换规则，OPEN 可直接改 CLOSED | `TicketService.update` | 当时只做 CRUD；工作流缺失 |
| P1-2 | 无操作日志，谁改了状态不可追溯 | 全模块 | 业务需要审计链 |
| P1-3 | 500 兜底分支不记日志，生产问题无法排查 | `GlobalExceptionHandler.handleOther` | 遗漏 |
| P1-4 | 更新无并发控制：两人同时编辑后写覆盖先写（lost update） | `TicketService.update` / `tickets` 表无 version 列 | 未设计乐观锁 |
| P1-5 | `creator`/`assignee` 是自由文本，与用户体系脱节 | `TicketCreateRequest` / tickets 表 | 用户模块未建 |

### P2 — 性能与可维护性

| # | 问题 | 位置 |
| --- | --- | --- |
| P2-1 | 测试只覆盖 Controller，Service 状态规则与 Repository SQL 无测试 | `src/test` |
| P2-2 | 无接口文档，前端靠 README 对照 | 工程化 |
| P2-3 | 前端无路由，视图靠 `v-if` 页签；无集中状态 | `App.vue` |
| P2-4 | 工单编号 4 位随机后缀，数据量大后撞号重试成本上升 | `TicketService.generateTicketNo` |
| P2-5 | 前端枚举文案（状态/优先级/类型）硬编码在前端，与后端两份真相 | `TicketManager.vue` |

### P3 — 体验与非必要优化

| # | 问题 |
| --- | --- |
| P3-1 | pom 编译目标 17，可升 21（运行已是 JDK 21） |
| P3-2 | 无软删除（删除即物理删） |
| P3-3 | Python 服务仅有健康检查，职责空转 |
| P3-4 | 无 Docker 化的一键启动（本机无 Docker，未验证） |

## 8. 问题产生的原因

- P0-1 / P1-1 / P1-2 / P1-5：项目按计划处于"先骨架后业务"阶段，认证与工作流本就排在后续阶段，属**计划内缺口**而非失误。
- P1-3 / P1-4 / P2-1：快速交付 CRUD 时的真实遗漏。
- P0-2：执行环境限制（密码不出现在对话与本工具进程空间），非代码问题。

## 9. 潜在风险

- 无认证状态下若将 8080 暴露到局域网，数据可被任意篡改。
- 无操作日志，出现纠纷时无法定责。
- 真实库未验收意味着 SQL 迁移脚本尚未在目标库执行过，首次执行可能暴露语法/权限问题。

## 10. 优化建议总表

按重构顺序实施，每步独立可回滚（Git 提交粒度 = 模块）。

| 顺序 | 模块 | 内容 | 对应问题 |
| --- | --- | --- | --- |
| 0 | 数据库验收 | 用户本机执行 secure 命令带密码启动；执行 `02-tickets.sql` | P0-2 |
| 1 | auth + user | 用户表、BCrypt、登录发 JWT、拦截器、ThreadLocal 上下文（afterCompletion 清理） | P0-1、P1-5 |
| 2 | ticket 加固 | 状态机（拒绝非法转换）、操作日志表、version 乐观锁、operator 来自登录上下文 | P1-1/2/4 |
| 3 | 持久层迁移 | JdbcTemplate → MyBatis（仅 Repository 层，Service 不变） | 6.9 |
| 4 | dispatch | 规则调度（技能/负载/优先级/SLA），纯 Java 可解释规则 | 新功能 |
| 5 | notification | SSE 推送工单事件 | 新功能 |
| 6 | job | `@Scheduled` 超时提醒/升级/自动关闭（条件更新保幂等） | 新功能 |
| 7 | analytics | 聚合 SQL + ECharts | 新功能 |
| 8 | ai | Java → FastAPI → DeepSeek；超时/降级/人工确认 | P3-3 |
| 9 | 工程化 | springdoc、异常日志补齐、Service/Repository 测试、编译目标 21 | P1-3、P2-1/2、P3-1 |

## 11. 目标架构（模块化单体）

不拆微服务，不拆 Maven 多模块，按业务包划分：

```
com.serviceflow.ai
├── common        响应/异常/上下文/工具（禁止堆业务）
├── auth          登录、JWT、拦截器、密码加密
├── user          用户与角色
├── ticket        工单 CRUD + workflow（状态机、操作日志）
├── dispatch      调度规则引擎（依赖 ticket、user 的 Service）
├── ai            DeepSeek 客户端封装（依赖 ticket 只读）
├── notification  SSE 推送
├── job           定时任务（依赖 ticket Service）
└── analytics     统计（只读 SQL）
```

依赖规则：上层可依赖下层 Service，**禁止跨层直连 Repository、禁止循环依赖**；`common` 不依赖任何业务包。

## 12. 重构顺序与每模块验收标准

| 模块 | 验收标准（真实执行） |
| --- | --- |
| 0 数据库 | `/api/health/ready` 200 且 mysql UP；02 脚本执行成功 |
| 1 auth+user | 登录返回 JWT；无 Token 调写接口 401；错误密码 401；密码字段永不出现于任何响应；测试覆盖登录/拦截器 |
| 2 ticket 加固 | 非法转换 400；每次变更写操作日志；并发更新第二个请求收到 409；构建+测试全绿 |
| 3 MyBatis | 全部既有测试不改仍通过；SQL 移入 XML/注解；PageHelper 分页结果与原实现一致 |
| 4 dispatch | 给定负载数据，分配结果符合规则表；规则单测覆盖 |
| 5 notification | 状态变更后 SSE 客户端 1s 内收到事件 |
| 6 job | 构造超时工单，定时任务执行后状态/通知符合预期；重复执行无副作用 |
| 7 analytics | 统计接口数值与手工 SQL 一致；前端图表渲染 |
| 8 ai | 断网/超时降级为人工提示；AI 建议不直接改状态，需人工确认落库 |

---

**本报告仅审计与规划，未修改任何业务代码。等待确认后再按第 10 节顺序逐模块实施。**

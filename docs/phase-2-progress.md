# 第二阶段进展记录：工单 CRUD

更新日期：2026-11-14
状态：**代码完成，Mock 测试与真实运行链路（除数据库写路径外）已验证；真实数据库读写验收待用户提供密码后补做**

## 一、本次新增

### 数据库

- [02-tickets.sql](../deploy/init/02-tickets.sql)：`tickets` 表（12 字段），utf8mb4，`ticket_no` 唯一索引，`status / priority / created_at` 普通索引，`IF NOT EXISTS` 可重复执行，不删已有数据。**尚未在本机 MySQL 执行**（需用户用应用账号执行）。

### Java 后端（包 `com.serviceflow.ai`）

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 公共 | `common/dto/ApiResponse.java`、`PageResponse.java` | 统一响应 `{code,message,data}` 与分页结构 |
| 公共 | `common/exception/NotFoundException.java`、`GlobalExceptionHandler.java` | 404 语义 + 全局异常 → 统一响应（400/404/500） |
| 枚举 | `ticket/enums/TicketType.java`、`Priority.java`、`TicketStatus.java` | 类型 4 种 / 优先级 4 档 / 状态 4 态 |
| Entity | `ticket/entity/Ticket.java` | 与 tickets 表一一对应 |
| DTO | `ticket/dto/TicketCreateRequest.java`、`TicketUpdateRequest.java`、`TicketResponse.java` | 入参校验（`@NotBlank/@Size`）与出参隔离 |
| Repository | `ticket/repository/TicketRepository.java` | JdbcTemplate，全部预编译占位符，KeyHolder 回填自增 id |
| Service | `ticket/service/TicketService.java` | `@Transactional`、编号生成（`SF+日期+4位随机`）、枚举校验、404 语义 |
| Controller | `ticket/controller/TicketController.java` | 5 个接口，只做参数接收与响应组装 |

依赖变化：pom 新增 `spring-boot-starter-validation`（用途：`@Valid` 请求体校验）。

### 前端

- `src/api/tickets.ts`：统一 `request` 封装（非 2xx 抛后端 message）+ 5 个 API 函数。
- `src/components/TicketManager.vue`：列表 / 状态与优先级筛选 / 分页 / 新建与编辑弹窗 / 删除确认。
- `App.vue`：顶部新增"首页 / 工单管理"页签切换（未引入 vue-router，单页内切换）。

## 二、实际验证结果（2026-11-14）

| # | 验证项 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | Maven 构建 | **通过** | `BUILD SUCCESS`，JDK 21.0.11（JAVA_HOME=D:\JDK21） |
| 2 | 自动化测试 | **通过 11/11** | `Tests run: 11, Failures: 0, Errors: 0`（2 健康检查 + 9 工单） |
| 3 | 前端构建 | **通过** | `npm run build`（vue-tsc + vite）exit 0 |
| 4 | POST 空标题 / 缺 type / 缺 title | **通过** | 均返回 HTTP 400 `{"code":400,"message":"标题不能为空/工单类型不能为空"}` |
| 5 | POST 合法 body（无 DB 密码） | **符合预期** | HTTP 500 `服务器内部错误`，无堆栈泄露（数据库不可用属预期，待密码验收） |
| 6 | GET 分页（无 DB 密码） | **符合预期** | 同上 500 |
| 7 | `GET /api/health` 回归 | **通过** | 200 `UP` |
| 8 | 经 Vite 代理调用工单接口 | **通过** | `http://127.0.0.1:5173/api/tickets` 返回后端真实响应 |
| 9 | 浏览器验证工单管理页 | **通过** | 筛选下拉/表格/分页/新建弹窗字段齐全；列表因 DB 不可用显示错误提示（预期）；首页"检查后端连接"绿色 UP 回归正常 |
| 10 | 真实数据库 CRUD | **未验证** | 需用户执行 02-tickets.sql 并以真实密码启动后端 |

## 三、已知限制与待办

1. **真实数据库读写未验收**：本机 MySQL 尚未执行 `02-tickets.sql`；后端需以 `MYSQL_PASSWORD` 启动。届时逐项验证：创建（201 + 自动生成编号）→ 分页/筛选 → 详情 → 更新 → 删除 → 删除后 404。
2. 修复记录：初版测试 `pageWithInvalidParamReturns400` 失败——standalone MockMvc 不触发 `@RequestParam` 的 AOP 方法校验（真实环境由 Spring 6.1 方法校验生效）。已补充 `HandlerMethodValidationException → 400` 处理，测试改为验证非法枚举 400 路径。
3. 曾观察到一次空标题返回 500 的瞬时异常，复测不再出现（疑似启动初期瞬时状态），持续观察。

## 四、下一阶段（第三阶段）规划（暂不实施）

DeepSeek API 接入：工单摘要、自动分类、优先级建议、处理建议。待第二阶段真实库验收通过后启动。

## 五、真实数据库 CRUD 验收（2026-11-14 18:05 实测，全部通过）

前提：用户本机执行 `01-init.sql`（建库）+ `02-tickets.sql`（建表，DESCRIBE 核验 12 列一致），以真实密码启动后端（ready 200 / mysql UP）。

| 步骤 | 结果 |
| --- | --- |
| POST /api/tickets | **201**，自动生成编号 `SF20261114-MK7W`，id=1，status 默认 OPEN，created_at/updated_at 由数据库填充 |
| GET /api/tickets?page=1&size=5 | **200**，total=1 |
| GET /api/tickets/1 | **200**，字段完整 |
| PUT /api/tickets/1 | **200**，status→IN_PROGRESS、assignee→agent-01 生效 |
| GET ?status=IN_PROGRESS&priority=HIGH | **200**，total=1（组合筛选生效） |
| DELETE /api/tickets/1 | 记录删除成功（PowerShell 非交互模式导致 cmdlet 显示异常，但请求已执行） |
| 删除后 GET /api/tickets/1 | **404** |
| 删除后 GET 列表 | total=0 |

**过程中的两个教训（已修复/已记录）**：
1. 曾误判"创建 500 = 数据库问题"，实际是我用 bash 风格的 `\"` 转义发 JSON，PowerShell 不解析该转义导致 Jackson 解析失败。PowerShell 下应使用 `Invoke-RestMethod` + 哈希表 `ConvertTo-Json`。
2. 500 发生时服务端无日志（审计 P1-3），已在 `GlobalExceptionHandler.handleOther` 补 `log.error` 堆栈输出，并配置 `logging.file.name=logs/serviceflow-backend.log`（提交 52ab6fd）。

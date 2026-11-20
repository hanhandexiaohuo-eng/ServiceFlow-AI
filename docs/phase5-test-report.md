# 第五阶段测试报告：AI 智能工单处理中心

- 测试日期：2026-11-16
- 测试范围：Vue 3 前端 → Spring Boot 后端（唯一权限边界）→ Python FastAPI → DeepSeek API
- 结论摘要：**全部验收项通过。** Java 123 项单测、Python 33 项 Mock 测试、前端类型检查与构建、接口权限/业务联调、浏览器页面验收均通过；配置有效 `DEEPSEEK_API_KEY` 后，真实 DeepSeek 完成五类 AI 能力的端到端成功联调，AI 建议经人工确认后由既有业务接口执行。

---

## 1. 环境与版本

| 组件 | 版本 / 状态 |
| --- | --- |
| OS | Windows（PowerShell 5.1） |
| JDK | 21.0.11（`D:\JDK21`，源码 `java.version=17`） |
| Spring Boot | 3.5.16，jar：`backend-java/target/serviceflow-backend-0.1.0.jar`（本次重新打包） |
| MySQL | 8.4 服务 `MySQL84` Running，库 `serviceflow` |
| Python | 3.11 + FastAPI 0.162.7 + Pydantic 2.14.3，`.venv` |
| 模型 | `deepseek-chat`，`https://api.deepseek.com` |
| 前端 | Vue 3.5 + Vite 7 + TS 5.9，dev server 5173 |
| 端口 | 前端 5173 / Java 8080 / Python 8000 |

凭据均只通过进程环境变量注入，未写入源码、文档、日志或数据库：

```powershell
# Java 后端
$env:MYSQL_HOST='127.0.0.1'; $env:MYSQL_PORT='3306'; $env:MYSQL_DATABASE='serviceflow'
$env:MYSQL_USER='serviceflow'; $env:MYSQL_PASSWORD='***'
$env:JWT_SECRET='***'          # ≥32 字节，否则应用拒绝启动
& 'D:\JDK21\bin\java.exe' -jar .\target\serviceflow-backend-0.1.0.jar

# Python AI 服务（唯一持有模型 Key 的进程）
$env:DEEPSEEK_API_KEY='***'    # 每次启动时注入，不落盘
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

---

## 2. 已通过（真实执行）

### 2.1 后端单元测试

`.\mvnw.cmd -B package` 实际结果：

| 指标 | 数值 |
| --- | --- |
| 测试套件 | 18 |
| 用例总数 | **123** |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 |

第五阶段新增 26 项：`AiTicketAccessGuardTest`(7)、`AiSuggestionServiceTest`(7)、`AiAnalysisServiceTest`(6)、`AiSessionServiceTest`(4)、`AiEndpointAuthTest`(2)。既有 97 项保持通过，第一至第四阶段未被破坏。

覆盖要点（Mock 依赖，不连真实 MySQL / 真实模型）：

- 逐单权限：ADMIN 全量、USER 仅本人创建、AGENT 创建人或处理人；
- 状态机不被绕过：采纳前先鉴权再 `workflowEngine.canEdit`，只经 `TicketService.update` 改 type/priority，不改 status/assignee；
- 并发采纳：`claim` 原子占用，重复采纳返回冲突并回滚为 PENDING；
- DISPATCH 仅 ADMIN；CHAT 不得走分析接口；
- 未登录访问 `/api/ai/**` → 401；请求体缺字段/类型错误 → 400。

### 2.2 Python 服务测试

`python -m pytest -q` → **33 passed**（0.98s），全部 Mock LLM 注入：

- 正常结构化响应 + Schema 校验；
- 超时 → `AI_TIMEOUT`；429/5xx 重试；重试耗尽 → `AI_UNAVAILABLE`；
- 缺 Key / Key 非法 → `AI_CONFIG_MISSING` / `AI_CONFIG_INVALID`；
- 进程内限流 → `AI_RATE_LIMITED`(429)；
- 非法 JSON、缺字段、空内容 → `AI_INVALID_OUTPUT` / `AI_EMPTY_RESPONSE`；
- Prompt Injection：用户输入中的指令性内容按数据处理，不进入 System 段；
- 业务校验：优先级小时仅 4/8/24/72；知识 `articleId`、调度 `agentId` 白名单。

### 2.3 前端类型检查与构建

```
vue-tsc --noEmit   → TSC_EXIT=0
vite build         → ✓ built in 8.50s   BUILD_EXIT=0
```

（仅有 chunk >500 kB 的提示，非错误。）

### 2.4 接口权限与业务联调（真实 MySQL）

| # | 请求 | 期望 | 实际 |
| --- | --- | --- | --- |
| 1 | `GET /api/ai/analyses` 未带 Token | 401 | **401** |
| 2 | `GET /api/ai/tickets/26/context` 未带 Token | 401 | **401** |
| 3 | `GET /api/ai/tickets/26/context`（ADMIN） | 200 + 工单/SLA/分析/建议 | **200**，`sla.state=OVERDUE` |
| 4 | `GET /api/knowledge?keyword=VPN` | 真实知识条目 | **200**，《VPN 连接失败的常见原因与处理》 |
| 5 | `POST /api/ai/sessions` | 201 | **201** |
| 6 | AGENT（非该单处理人）发起分析 | 403 | **403** |
| 7 | AGENT（该单处理人钱二）请求 DISPATCH | 403（仅 ADMIN） | **403**「仅管理员可执行该操作」 |
| 8 | AGENT（钱二）读取本人处理工单上下文 | 200 | **200** |
| 9 | USER 查看他人发起的分析记录 | 403 | **403** |
| 10 | ADMIN 查看分析详情 | 200 | **200** |

说明：USER `bob` 即工单 26 创建人「小明」，其访问本人工单返回 200 属正确行为；访问他人分析记录被拒，逐单权限符合设计。

### 2.5 真实 DeepSeek 联调（成功态）

Key 配置于 Python 进程环境（`/health/ready` 返回 `UP`、`model=deepseek-chat`、6 个 Prompt 版本加载）。经 Java 后端发起，结果如下：

| 能力 | 接口 | 结果 |
| --- | --- | --- |
| 工单智能分析 | `POST /api/ai/analyses`（TICKET_ANALYSIS，ticket 26） | **201 / SUCCESS**，`promptVersion=ticket_analysis@v1`、`model=deepseek-chat`、`latencyMs=4901`；摘要「安全软件弹窗告警…疑似勒索病毒感染…已断网」；`recommendedType=INCIDENT`、`recommendedPriority=URGENT`、`confidence=0.85`；生成 1 条 PENDING 分类建议、推荐 1 篇真实知识 |
| 优先级建议 | 同上（PRIORITY） | **SUCCESS**，`recommendedPriority=URGENT`、`suggestedResponseHours=4`、`confidence=0.9`；输出风险 3 条、SLA 影响分析（引用系统计算的 OVERDUE 与截止时间） |
| 处理方案生成 | 同上（SOLUTION） | **SUCCESS**，输出可能原因 6 条、诊断步骤、工具、风险、验证项、前置条件；`confidence=0.55` |
| AI 调度辅助 | 同上（DISPATCH，PENDING 工单 22） | **SUCCESS**，`dispatch` 返回第四阶段规则推荐（候选赵一 71.5 / 钱二 61.3、权重、排除原因），`result.assist` 返回 AI 综合说明，`recommendedAgentId=3`、`confidence=0.72`，明确「最终分配需由有权限用户确认」 |
| AI 会话语义问答 | `POST /api/ai/sessions/2/messages` | **200**，ASSISTANT 回答以工单真实上下文（URGENT、ASSIGNED、处理人钱二、SLA 剩余 -75 分钟）为依据，并明确说明上下文未提供的信息不作臆测 |
| 人工确认闭环 | `POST /api/ai/suggestions/1/accept` | **200**，建议状态 `EXECUTED`，`executionNote=已采纳：系统管理员 将工单类型更新为 INCIDENT`；工单 `status=ASSIGNED`、`assignee=钱二` 未被 AI 改动 |

说明：ticket 26 采纳分类建议时类型本就是 `INCIDENT`，因此业务侧无字段变化（`updatedAt` 未变），属幂等正确行为；优先级建议（PENDING）与处理方案建议保留待人工决策，未自动执行。

### 2.6 浏览器端到端验收

以部署环境变量初始化的管理员账号登录 `http://127.0.0.1:5173`（初始密码不写入任何文档）：

| 页面 | 验收项 | 结果 |
| --- | --- | --- |
| AI 工作空间 `#/ai/workspace?ticketId=26` | 三栏布局、工单 26 上下文、SLA 超时提示、待确认建议（含采纳/拒绝）、推荐知识、最近分析 | **通过**，无白屏、无致命控制台错误 |
| 分析详情 `#/ai/analyses/4` | 真实结构化结果：推荐分类/问题类型/优先级/置信度、摘要、关键信息 6 条、推荐理由 5 条、技术领域、所需技能、影响范围、关键词、推荐知识「终端疑似勒索病毒的隔离与上报 99%」、AI 建议（已执行态）、元信息（deepseek-chat / ticket_analysis@v1 / 4901 ms） | **通过** |
| AI 分析历史 `#/ai/history` | 成功与失败记录并存，失败记录展示 `AI_CONFIG_INVALID` | **通过** |
| 知识库 `#/knowledge` | 搜索 VPN → 1 条真实文章（分类/标签/更新时间正确） | **通过** |

### 2.7 前四阶段回归（只读）

`/api/tickets`、`/api/dispatch/history`、`/api/agents/workload`、`/api/skills`、`/api/knowledge/categories`、`/api/ai/sessions` 均返回 **200**；后端 123 项测试与前端构建通过，未发现回归。

---

## 3. 真实模型联调过程记录（先失败后通过）

首次联调时环境中的 `DEEPSEEK_API_KEY` 长度 36、不以 `sk-` 开头，请求 DeepSeek 返回 401，Python 映射为：

```json
{"error":{"code":"AI_CONFIG_INVALID","message":"模型鉴权失败，请检查 DEEPSEEK_API_KEY","retryable":false}}
```

Java 透传为 HTTP 503，响应头 `X-AI-Error-Code: AI_CONFIG_INVALID`，`ai_analyses` 落 `status=FAILED`（`latencyMs≈2433`），前端展示可读错误且不白屏——**期间未用任何 Mock 结果冒充真实分析**。

随后以有效 Key（`sk-` 开头）重启 Python 服务，按第 2.5 节完成成功态联调，原有 FAILED 记录仍保留在分析历史中，形成可追溯的失败/成功对照。

---

## 4. 未覆盖 / 不适用

| 项 | 归类 | 说明 |
| --- | --- | --- |
| 重复采纳同一建议的幂等性 | **Mock 验证** | `AiSuggestionServiceTest` 覆盖（`claim` 原子占用 + 回滚），未在真实并发下压测 |
| 用户级限流 10 次/分钟（Java）、进程内 12 次/分钟（Python） | **Mock 验证** | 均有单测；真实触发需连续高频请求，本次未构造 |
| 知识库管理员新增/编辑/关联工单 | **尚未测试** | 页面与接口已实现；为避免污染真实数据，本次未执行写操作 |
| AI 调度辅助「确认分配」 | **不在本阶段范围** | 沿用第四阶段人工确认分配，AI 仅提供建议，不直接改处理人 |
| RAG / 向量检索 | **不适用** | 第一版为 MySQL 关键词检索，代码与文案均未宣称 RAG/向量检索 |

---

## 5. 本次发现并修复的问题

| # | 问题 | 处理 |
| --- | --- | --- |
| 1 | 后端启动报 `JWT_SECRET 未配置或长度不足 32 字节` | 注入 ≥32 字节密钥后启动 |
| 2 | 运行中的 jar 为旧版本，`/api/ai/**` 全部 404（日志 `No static resource api/ai/...`） | 停止旧进程解锁 jar → 重新 `mvnw package` → 重启 |
| 3 | PowerShell 向 `curl.exe` 传 `-d '{...}'` 时引号被吞，后端报 `JsonParseException` → 500 | 改用 `--data-binary @文件` |
| 4 | 旧后端/Python 进程随父 shell 退出被回收 | 改为长驻后台任务启动；凭据仅存在进程环境 |
| 5 | 环境 `DEEPSEEK_API_KEY` 无效导致 `AI_CONFIG_INVALID` | 由用户提供有效 Key 后重启 Python 服务，完成成功态联调 |

---

## 6. 交付判定

| 维度 | 判定 |
| --- | --- |
| 架构与权限边界（Java 唯一业务入口） | 通过 |
| AI 建议不绕过状态机 / SLA / 人工确认 | 通过（单测 + 真实采纳验证） |
| 前后端联调 | 通过 |
| 真实模型联调（五类能力 + 会话问答） | **通过** |
| 前端四页面（加载 / 空 / 异常 / 成功态） | 通过 |
| 密钥安全（前端、仓库、日志均无 Key） | 通过 |
| 既有功能回归 | 通过 |

**结论：第五阶段（AI 智能工单处理中心）开发与验收完成，全部功能与真实 DeepSeek 端到端联调通过。**

> 联调产生的数据：`ai_analyses` id=4~7（SUCCESS）、id=1~3（FAILED，历史保留）、`ai_suggestions` id=1（EXECUTED）及其余 PENDING 建议、`ai_sessions` id=1~2 与对应消息。如需清理测试数据，可按上述 id 在确认无引用后删除。

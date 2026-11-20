# Phase 4 Spec：智能调度引擎 + 人员负载管理

状态：待评审
依据：[phase4-audit.md](../phase4-audit.md)
目标：在现有 Spring Boot 3.5 + Vue 3 项目上，交付可运行的人员管理、真实负载、规则调度、人工确认分配与调度历史；不重建项目、不绕过现有工作流。

## 1. 范围

### 1.1 交付

- 人员档案与工作状态（AVAILABLE/BUSY/OFFLINE/DISABLED），与账号启用状态严格区分。
- 技能字典与人员技能熟练度（1–4），管理员可扩展技能。
- 真实负载统计：占用量、负载率、等级、今日/本周完成、平均处理时长、SLA 达成率、是否可接单。
- 规则调度：PENDING 工单候选过滤 + 四维评分 + 可解释推荐 + 排除原因。
- 人工确认分配：复用 `TicketWorkflowService.assign`，服务端重新校验，写调度日志。
- 调度历史查询。
- 四个前端页面：调度工作台、人员管理、人员负载、调度历史。

### 1.2 非目标

- 不做 AI/大模型调度，不接 DeepSeek。
- 不做已分配工单的重新调度工作台（继续使用现有「转交」）。
- 不做人员删除、账号禁用、密码重置。
- 不改动 Phase 1–3 的仪表盘、工单、工作流、SLA 行为（除下文 D2 的分配资格收紧）。

## 2. 关键设计决策（需确认）

| 编号 | 决策 | 理由 / 影响 |
|---|---|---|
| D1 | `agent_profiles.user_id` 即人员标识，主键 = users.id；接口里的 agentId 就是用户 id | 避免 agent_id 与 user_id 双主键映射，历史工单 `assignee_id` 可直接复用 |
| D2 | 新分配/转交只允许 `ADMIN/AGENT`；调度候选只允许 `AGENT` | 落实「不允许分配给无权限人员」。**影响**：USER 账号（如 bob）不能再被新分配；历史工单不变 |
| D3 | 推荐与确认分离；确认时重新跑一次过滤 | 防止读取推荐后状态变化；分配仍由状态机条件更新兜底 |
| D4 | 工单所需技能来自 `ticket_type_skill_rules`，不从标题做字符串匹配 | 规则可配置、可解释，支持多技能 |
| D5 | 人员工作状态独立于 `users.status`；账号禁用时有效状态强制为 DISABLED | 前端置 AVAILABLE 也不能绕过账号禁用 |
| D6 | 绩效样本不足（完成 < 5 单）使用中性分 60 并标注，不编造数据 | 满足「新员工不被永久排除」且不伪造指标 |
| D7 | 调度推荐/确认接口仅 ADMIN；AGENT 可查看人员列表与负载、改自己可用状态 | 最小权限；普通创建人仍可用现有分配接口 |
| D8 | 不在 SQL 中插入假人员；验收时由管理员通过接口创建 3 个真实 AGENT 账号 | 遵守「不允许静态假数据冒充真实人员」 |
| D9 | 新增 `dispatch_logs` 记录 RECOMMEND 与 ASSIGN 快照（含分数明细 JSON） | 调度历史与可解释性可追溯 |
| D10 | 权重与负载阈值放在 `application.yml` 的 `dispatch.*`，支持配置 | 满足阈值可配置，不写死 |

## 3. 数据库设计

新增迁移 `deploy/migrations/20261116-phase4-agent-dispatch.sql`，并同步 `deploy/init/05-agent-dispatch.sql`。全部 `CREATE TABLE IF NOT EXISTS`，种子用 `ON DUPLICATE KEY UPDATE`，可重复执行。

```sql
agent_profiles (
  user_id BIGINT UNSIGNED PK,              -- FK users.id
  max_concurrent INT NOT NULL DEFAULT 5,   -- CHECK 由应用保证 >=1
  availability VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
  created_at, updated_at,
  KEY idx_agent_profiles_availability (availability)
)

agent_skills (
  id BIGINT UNSIGNED PK AI,
  skill_code VARCHAR(32) NOT NULL UNIQUE,  -- JAVA / DATABASE / ...
  skill_name VARCHAR(64) NOT NULL UNIQUE,
  description VARCHAR(255) NULL,
  created_at
)

agent_skill_relations (
  id BIGINT UNSIGNED PK AI,
  agent_id BIGINT UNSIGNED NOT NULL,       -- users.id
  skill_id BIGINT UNSIGNED NOT NULL,
  proficiency_level TINYINT NOT NULL,      -- 1..4
  created_at,
  UNIQUE KEY uk_agent_skill (agent_id, skill_id),
  KEY idx_agent_skill_skill (skill_id)
)

ticket_type_skill_rules (
  id BIGINT UNSIGNED PK AI,
  ticket_type VARCHAR(32) NOT NULL,        -- INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT
  skill_id BIGINT UNSIGNED NOT NULL,
  required TINYINT NOT NULL DEFAULT 1,     -- 1 必需，0 加分项
  UNIQUE KEY uk_type_skill (ticket_type, skill_id)
)

dispatch_logs (
  id BIGINT UNSIGNED PK AI,
  ticket_id BIGINT UNSIGNED NOT NULL,
  ticket_no VARCHAR(32) NOT NULL,
  action VARCHAR(16) NOT NULL,             -- RECOMMEND / ASSIGN
  operator_id BIGINT UNSIGNED NULL,
  operator_name VARCHAR(64) NOT NULL,
  agent_id BIGINT UNSIGNED NULL,
  agent_name VARCHAR(64) NULL,
  score DECIMAL(5,1) NULL,
  candidate_count INT NULL,
  note VARCHAR(500) NULL,
  detail JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_dispatch_logs_ticket (ticket_id, id),
  KEY idx_dispatch_logs_agent (agent_id),
  KEY idx_dispatch_logs_created (created_at)
)
```

技能种子（8 个）：`JAVA/Java 开发`、`DATABASE/数据库`、`NETWORK/网络运维`、`SYS_OPS/系统运维`、`FRONTEND/前端开发`、`SERVER/服务器管理`、`SOFTWARE_SUPPORT/软件使用支持`、`OTHER/其他技能`。

类型—技能规则种子：

| 工单类型 | 必需 | 加分项 |
|---|---|---|
| INCIDENT | 系统运维 | 网络运维 |
| SERVICE_REQUEST | 软件使用支持 | 服务器管理、前端开发 |
| CHANGE | 服务器管理 | 数据库、系统运维、Java 开发 |
| CONSULT | 软件使用支持 | — |

不插入任何人员、档案、技能关系、绩效数据。

## 4. 配置（application.yml）

```yaml
dispatch:
  weights:
    skill: 0.40
    workload: 0.30
    sla: 0.20
    performance: 0.10
  load:
    low-max: 49        # 0-49 低
    normal-max: 79     # 50-79 正常
    high-max: 99       # 80-99 高；>=100 满
  performance:
    sample-threshold: 5
    neutral-score: 60
    full-volume: 10
  top-limit: 5
```

`DispatchProperties` 用 `@ConfigurationProperties(prefix = "dispatch")` 绑定；权重和不等于 1 时按比例归一化。

## 5. 负载口径

- 占用：`ASSIGNED + ACCEPTED + PROCESSING`，按 `assignee_id` 聚合。
- `loadRate = round(occupied / maxConcurrent * 100, 1)`，可超过 100。
- 等级：`LOW < low-max+1`，`NORMAL < normal-max+1`，`HIGH < high-max+1`，其余 `FULL`。
- `canAccept = users.status=1 && effectiveAvailability=AVAILABLE && occupied < maxConcurrent`。
- 今日完成：`status IN (RESOLVED, CLOSED)` 且 `COALESCE(resolved_at, closed_at) >= 今日 00:00`。
- 本周完成：同上，起点为本周一 00:00。
- 平均处理时长：`AVG(TIMESTAMPDIFF(MINUTE, COALESCE(first_response_at, created_at), COALESCE(resolved_at, closed_at)))`，无样本返回 null。
- SLA 达成率：完成单中 `完成时间 <= sla_due_at` 的占比，无样本返回 null。
- `effectiveAvailability`：账号 `status=0` → DISABLED，否则取档案值。

## 6. 调度算法（全部 0–100，可解释）

### 6.1 候选过滤（任一不满足即进入 excluded，并给出原因）

1. 角色必须是 `AGENT`（含人员档案）。
2. 账号 `status=1`。
3. 有效状态必须是 `AVAILABLE`（BUSY/OFFLINE/DISABLED 排除）。
4. 具备该工单类型的**全部必需技能**。
5. `occupied < maxConcurrent`。

### 6.2 评分

- `SkillScore = 0.7 × 必需技能平均熟练度分 + 0.3 × 加分技能平均熟练度分`；无加分规则时为必需项均分。
  熟练度分：1→60，2→75，3→90，4→100。
- `WorkloadScore = clamp(0,100, 100 × (1 - occupied / maxConcurrent))`。
- `SLAScore`（工单级，直接调用 `TicketSlaService.evaluate`）：
  - OVERDUE → 100
  - WARNING → `70 + 30 × (1 - max(0, remainingMinutes) / 120)`
  - ON_TRACK → `clamp(0,70, 70 × (1 - remainingMinutes / 总时限分钟))`
  - COMPLETED → 0
- `PerformanceScore`：
  - 完成样本 < `sample-threshold` → `neutral-score`（60），并产生 warning。
  - 否则 `0.4 × 完成量分 + 0.4 × SLA 达成率分 + 0.2 × 速度分`
    - 完成量分 = `min(100, completed / full-volume × 100)`
    - SLA 达成率分 = `achievedRate × 100`
    - 速度分 = `clamp(0,100, 100 × (1 - 平均处理分钟 / 平均SLA窗口分钟))`，窗口 <=0 时取 60。
- `total = Σ(score × weight)`，保留 1 位小数。

### 6.3 排序与返回

按 `total DESC, loadRate ASC, agentId ASC`；默认取前 `top-limit`。
每个候选返回：agentId、姓名、用户名、有效状态、负载率、占用量/上限、总分、四项分、匹配技能、缺失加分技能、`reasons[]`、`warnings[]`。
响应同时返回 `requiredSkills`、`excluded[]`、`weights`、SLA 紧迫度，保证可解释。

## 7. 后端 API 契约

所有响应仍为 `{code,message,data}`。除登录/健康检查外均需 Bearer Token。

### 7.1 人员与技能

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/agents?keyword=&availability=&skillCode=` | ADMIN/AGENT | 人员列表（含技能、有效状态） |
| GET | `/api/agents/{id}` | ADMIN/AGENT | 人员详情，id=userId |
| POST | `/api/agents` | ADMIN | 创建 AGENT 账号或提升已有用户；可同时设技能 |
| PUT | `/api/agents/{id}` | ADMIN | 修改 maxConcurrent / availability |
| GET | `/api/agents/{id}/skills` | ADMIN/AGENT | 技能列表 |
| PUT | `/api/agents/{id}/skills` | ADMIN | 全量替换技能（事务） |
| PUT | `/api/agents/{id}/availability` | ADMIN；AGENT 仅本人 | AGENT 不能设 DISABLED |
| GET | `/api/skills` | 登录用户 | 技能字典 |
| POST | `/api/skills` | ADMIN | 新增自定义技能 |

创建请求：

```json
{
  "userId": 5,
  "username": "agent01",
  "name": "张伟",
  "password": "agent123",
  "maxConcurrent": 5,
  "availability": "AVAILABLE",
  "skills": [{ "skillId": 1, "proficiencyLevel": 3 }]
}
```

- 有 `userId`：提升该启用用户为 AGENT 并建档；已有档案返回 409。
- 无 `userId`：`username/name/password` 必填，密码 6–72 字符，用户名唯一；新用户 role=AGENT。
- `maxConcurrent` 1–50，默认 5。
- 普通 USER 调用一律 403；请求体不接受 role 字段。

### 7.2 负载

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/agents/workload` | ADMIN/AGENT | 全部 AGENT 负载 |
| GET | `/api/agents/{id}/workload` | ADMIN/AGENT | 单人负载 |
| GET | `/api/agents/workload/overview` | ADMIN/AGENT | 汇总：总人数、可接单数、各级负载人数、平均负载率、总占用、待分配工单数 |

### 7.3 调度

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/dispatch/tickets?page=&size=` | ADMIN | PENDING 工单池（复用 TicketService） |
| GET | `/api/dispatch/recommendations/{ticketId}?limit=5` | ADMIN | 推荐；非 PENDING 返回 400；写 RECOMMEND 日志 |
| POST | `/api/dispatch/assign` | ADMIN | 确认分配；重新校验后调用工作流；写 ASSIGN 日志 |
| GET | `/api/dispatch/history?page=&size=&ticketId=&agentId=` | ADMIN | 调度历史分页 |

确认分配请求：`{ "ticketId": 21, "agentId": 3, "note": "按推荐分配" }`
成功返回 201：`{ "ticket": TicketResponse, "logId": 12, "chosen": DispatchCandidateResponse }`。

失败语义：

- 非 PENDING → 400；
- 人员不可接单/技能不匹配/已满 → 400，message 明确指出第一条不满足项；
- 状态已被他人流转 → 409（工作流条件更新）。

## 8. 前端设计

| 路由 | 视图 | 能力 |
|---|---|---|
| `/dispatch` | `DispatchWorkbenchView.vue` | 左：PENDING 工单列表（优先级/SLA）；右：推荐榜、四项分数条、原因/警告、排除名单；确认分配 |
| `/dispatch/agents` | `AgentManagementView.vue` | 人员表格、创建/提升、最大并发、可用状态、技能编辑（技能来自 API） |
| `/dispatch/workload` | `AgentWorkloadView.vue` | 汇总卡片 + 负载表 + 负载条 + 今日/本周完成/平均时长/可接单 |
| `/dispatch/history` | `DispatchHistoryView.vue` | RECOMMEND/ASSIGN 记录分页，展示工单、人员、分数、操作人 |

- 菜单「智能调度」四项改为真实路由，移除 `soon`。
- 非 ADMIN 访问调度页显示「仅管理员可操作」提示，不隐藏菜单。
- 所有技能选项来自 `/api/skills`，前端不硬编码技能类别。
- 复用 `DataTable/StatCard/SlaBadge/PriorityTag/AppIcon` 与现有设计变量。
- 新增 `LoadBar.vue`（负载条）与 `constants/dispatch.ts`（状态/等级/动作标签与颜色）。

## 9. 测试

后端：

- `DispatchScoringServiceTest`：权重求和、技能缺失排除、负载分裁剪、绩效中性分、多技能。
- `WorkloadServiceTest`：负载率/等级/canAccept 边界（0、49、50、79、80、99、100、超限）。
- `AgentServiceTest`：USER 不能创建/提升；AGENT 不能设 DISABLED；ADMIN 可管理；非本人不能改可用状态。
- `DispatchServiceTest`：仅 PENDING 可推荐；确认分配走 `TicketWorkflowService.assign`；记录 ASSIGN 日志；不可接单拒绝。

前端：`npm run build` 通过；浏览器验收四页与关键交互。

## 10. 验收步骤

1. 执行迁移脚本（需要 MySQL 凭据），核对 5 张新表与种子数据。
2. `.\mvnw.cmd -q test` 全绿；打包。
3. 重启后端（JWT_SECRET ≥32 字节、MYSQL_PASSWORD），`/api/health/ready` 为 UP。
4. `.tools/phase4-smoke.ps1`：
   - admin 登录；创建 3 个 AGENT；配置技能；
   - 创建技能不匹配的 AGENT，验证被排除；
   - 创建 PENDING 工单；
   - 获取推荐，校验排名/分数/原因；
   - 确认分配，校验工单变 ASSIGNED、事件与调度日志；
   - 负载接口校验占用与负载率；
   - 负例：USER 调调度接口 403、USER 创建人员 403、AGENT 设 DISABLED 403、非 PENDING 推荐 400。
5. 前端构建 + 浏览器验收，截图存 `docs/phase4/screenshots/`。
6. 更新审计文档的「验证结果」，新增 `docs/phase4/acceptance.md`。

## 11. 阻塞项与待确认

1. **MySQL 凭据**：当前会话拿不到 `MYSQL_PASSWORD`，迁移与重启验证需要用户提供一次（或确认可用的启动方式）。
2. **D2 收紧分配资格**：确认 USER 不再可被新分配。
3. **D7 调度仅 ADMIN**：确认 AGENT 不参与调度台操作。
4. **D8 验收数据**：确认用管理员接口创建 3 个 AGENT 账号（而非 SQL 假数据）。

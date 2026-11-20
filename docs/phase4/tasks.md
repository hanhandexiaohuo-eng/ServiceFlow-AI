# Phase 4 Tasks

依据：[spec.md](spec.md) · [审计](../phase4-audit.md)

## [x] T1 数据库迁移与初始化

- 新增 `deploy/migrations/20261116-phase4-agent-dispatch.sql`
- 新增 `deploy/init/05-agent-dispatch.sql`（内容与迁移一致）
- 建表：`agent_profiles`、`agent_skills`、`agent_skill_relations`、`ticket_type_skill_rules`、`dispatch_logs`
- 种子：8 个技能 + 4 类工单的技能规则；不插入人员/绩效
- 验收：脚本可在空库重复执行两次不报错；`SHOW TABLES` 可见 5 张新表

## [x] T2 后端基础模型

- `dispatch/enums`：`AgentAvailability`、`LoadLevel`、`DispatchAction`
- `dispatch/entity`：`AgentProfile`、`AgentSkill`、`AgentSkillRelation`、`TicketTypeSkillRule`、`DispatchLog`
- `dispatch/dto`：6 个请求记录（创建/更新/技能/可用状态/技能字典/确认分配）
- `dispatch/vo`：人员、技能、负载、评分、候选、排除、推荐、确认、日志共 11 个响应记录
- `dispatch/config/DispatchProperties`，并在 `application.yml` 增加 `dispatch.*`
- 验收：`mvnw -q test-compile` 通过

## [x] T3 人员与技能管理

- 仓储：`AgentProfileRepository`、`AgentSkillRepository`
- 服务：`AgentService`、`SkillCatalogService`
- 控制器：`AgentController`、`SkillController`
- 权限：
  - 创建/提升/改档案/改技能：ADMIN
  - 列表/详情/技能查询：ADMIN 或 AGENT
  - 可用状态：ADMIN 任意；AGENT 仅本人且不能 DISABLED
  - USER 一律 403，请求体无法注入 role
- 修改 `UserRepository`（findAll/findByRole/updateRole）、`UserQueryService`、`UserController`、`TicketWorkflowService`
- 验收：单测覆盖角色边界；`GET /api/agents` 返回技能与有效状态

## [x] T4 负载统计

- `WorkloadRepository`：占用、今日/本周完成、平均处理时长、SLA 达成率聚合
- `WorkloadService`：负载率、等级、canAccept、有效可用状态
- 接口：`/api/agents/workload`、`/{id}/workload`、`/workload/overview`
- 验收：单测覆盖 0/49/50/79/80/99/100/超限边界；数据全部来自 tickets/users

## [x] T5 调度引擎

- `TicketTypeSkillRuleRepository`：读取类型所需技能
- `strategy`：`SkillMatchStrategy`、`WorkloadScoreStrategy`、`SlaUrgencyScoreStrategy`、`PerformanceScoreStrategy`，统一 `ScoreStrategy`
- `DispatchService`：
  - 仅 PENDING 可推荐
  - 候选过滤（AGENT、启用、AVAILABLE、技能、未满）
  - 四维评分、排序、原因/警告、排除原因
  - 生成 RECOMMEND 日志
- 验收：单测核对权重、技能缺失排除、中性绩效、排序

## [x] T6 确认分配与调度历史

- `DispatchLogRepository`
- `POST /api/dispatch/assign`：重新校验 → 调用 `TicketWorkflowService.assign` → 写 ASSIGN 日志
- `GET /api/dispatch/history` 分页
- `GET /api/dispatch/tickets` 复用 `TicketService.page`
- 验收：单测验证走工作流、写日志、非 PENDING/不可接单拒绝；并发冲突返回 409

## [x] T7 后端测试与构建

- 新增 4 个测试类（评分、负载、人员权限、调度服务）
- `.\mvnw.cmd -q test` 全绿
- `.\mvnw.cmd -q package` 成功
- 验收：测试与构建输出无失败

## [x] T8 前端 API 与基础组件

- `api/agents.ts`、`api/dispatch.ts`、`constants/dispatch.ts`
- `components/LoadBar.vue`
- `constants/ticket.ts` 增加 AGENT 标签
- `AssignDrawer.vue` 改用 `?assignable=true`
- 验收：`npm run build` 通过

## [x] T9 前端页面与路由

- `DispatchWorkbenchView.vue`、`AgentManagementView.vue`、`AgentWorkloadView.vue`、`DispatchHistoryView.vue`
- 菜单四项真实路由；注册路由
- 非 ADMIN 提示；技能来自 `/api/skills`
- 验收：四页可打开，无控制台错误；构建通过

## [x] T10 执行迁移与运行时验收

- 获取 MySQL 凭据，执行迁移并核对表/种子
- 重启后端（JWT_SECRET、MYSQL_PASSWORD），健康检查 UP
- `.tools/phase4-smoke.ps1` 覆盖正例与负例
- 前端构建、浏览器验收、截图 `docs/phase4/screenshots/`
- 更新审计验证结果，新增 `docs/phase4/acceptance.md`

## 依赖顺序

T1 → T2 → T3/T4 → T5 → T6 → T7 → T8 → T9 → T10

## 完成定义

- 5 张新表与种子数据存在且可重复迁移
- 人员、技能、负载、推荐、确认、历史接口均返回真实数据
- 分配最终由 `TicketWorkflowService.assign` 完成，调度日志可追溯
- 后端测试、后端打包、前端构建全部通过
- 四个页面通过浏览器验收并有截图


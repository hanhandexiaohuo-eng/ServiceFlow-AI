# Phase 4 验收报告：智能调度引擎 + 人员负载管理

- 验收日期：2026-11-16
- 环境：Windows / Java 21 / Spring Boot 3.5.16 / MySQL 8.4 / Vue 3 + Vite
- 后端：`http://127.0.0.1:8080`
- 前端：`http://127.0.0.1:5173`
- 数据库：`serviceflow`（本地 MySQL84，用户 `serviceflow`）

## 1. 数据库迁移

执行脚本：`deploy/migrations/20261116-phase4-agent-dispatch.sql`（可重复执行）。

| 项目 | 结果 |
|---|---|
| `agent_profiles` | 已创建 |
| `agent_skills` | 已创建，种子 8 条 |
| `agent_skill_relations` | 已创建 |
| `ticket_type_skill_rules` | 已创建，种子 10 条（覆盖 INCIDENT / SERVICE_REQUEST / CHANGE / CONSULT） |
| `dispatch_logs` | 已创建 |

未修改 `users`、`tickets` 既有列，无破坏性 DDL。

## 2. 自动化测试与构建

| 项目 | 命令 | 结果 |
|---|---|---|
| 后端单元测试 | `.\mvnw.cmd -q test` | 97 个测试全部通过 |
| 后端打包 | `.\mvnw.cmd -q -DskipTests package` | 成功，fat jar 27,141,881 字节 |
| 前端类型检查与构建 | `node "D:\Node\node_modules\npm\bin\npm-cli.js" run build` | 成功（vue-tsc + vite） |

新增测试覆盖：评分权重归一化、四维公式与边界、技能必需/加分、负载 0/49/50/79/80/99/100/超限、中性绩效、仅 PENDING 参与推荐、排除原因、确认分配走工作流并写日志、AGENT/ADMIN 权限负例。

## 3. 接口冒烟

脚本：`.tools/phase4-smoke.ps1`（幂等，凭据仅通过环境变量传入）。

结果：**23 项通过，0 失败**。

覆盖：

- 管理员登录；未登录访问 `/api/agents` 返回 401。
- 技能字典非空；通过 `POST /api/agents` 创建 3 个真实 AGENT（赵一 / 钱二 / 孙三）并绑定技能与熟练度。
- `/api/agents/workload`、`/api/agents/workload/overview` 返回真实占用与汇总。
- 待分配工单池 → 生成推荐（含所需技能、四维分数、排除名单）→ 确认分配 → 工单变为 ASSIGNED → 调度日志落库 → 历史可查。
- 已分配工单再次推荐返回 400。
- AGENT 生成推荐 / 创建人员 / 自设 DISABLED 均返回 403；AGENT 可将本人状态改为 BUSY。
- 全局调度历史可查。

本次冒烟产生的业务数据：3 个 AGENT 账号；1 张 PENDING 工单（SF20261116-YWEF）被真实分配；2 条 RECOMMEND + 1 条 ASSIGN 调度日志。

## 4. 浏览器验收

登录 `admin` 后逐页验证，截图位于 `docs/phase4/screenshots/`。

| 页面 | 路由 | 验收点 | 截图 |
|---|---|---|---|
| 调度工作台 | `/#/dispatch` | 待分配工单池、推荐生成、候选排序、四维分数、排除原因 | `01-dispatch-workbench.png`、`02-dispatch-recommendation.png` |
| 人员管理 | `/#/dispatch/agents` | 3 名人员、技能与熟练度、有效状态、创建/提升/编辑入口 | `03-agent-management.png` |
| 人员负载 | `/#/dispatch/workload` | 总人数/可接单/繁忙/待分配/平均负载率、占用与负载条、绩效列 | `04-agent-workload.png` |
| 调度历史 | `/#/dispatch/history` | 推荐与分配审计记录、人员、分数、候选数、操作人、备注 | `05-dispatch-history.png` |

浏览器证据（摘要）：

- 推荐页显示工单 `SF20261116-WUUQ`（紧急、WARNING、剩余 40 分钟），所需技能「系统运维（必需）/ 网络运维（加分）」，权重 40/30/20/10，候选「钱二」总分 77.8，排除名单明确标注「缺少必需技能：系统运维」。
- 负载页显示 3 人、待分配 5 单、平均负载率 16.7%，钱二占用 1/2、负载率 50.0%。
- 历史页显示 3 条记录，含「生成推荐」与「确认分配」。

## 5. 权限矩阵（已验证）

| 操作 | ADMIN | AGENT | USER |
|---|---|---|---|
| 人员列表 / 详情 / 负载 | 允许 | 允许 | 禁止 |
| 创建人员 / 提升已有用户 / 编辑技能 | 允许 | 禁止 | 禁止 |
| 修改可用状态 | 任意人员 | 仅本人，且不可 DISABLED | 禁止 |
| 调度推荐 / 确认分配 / 调度历史 | 允许 | 禁止 | 禁止 |

账号禁用优先于人员档案状态：`users.status=0` 时有效状态强制为 `DISABLED`。

## 6. 过程中修复的问题

1. `ticket_type_skill_rules.required` 为 TINYINT，MySQL 驱动按 Boolean 返回，`BeanPropertyRowMapper` 转 Integer 失败导致推荐 500。
   - 修复：`TicketTypeSkillRuleRepository` 改用显式 `RowMapper`（`rs.getInt("required")`），并在注释中说明原因。
   - 复测：冒烟推荐与分配全部通过。

## 7. 运行说明与遗留

- 后端当前以后台进程运行，启动时注入了 `MYSQL_*` 与新的开发用 `JWT_SECRET`；数据库密码未写入任何项目文件。
- `JWT_SECRET` 已更换，旧浏览器登录态失效，需要重新登录（本次已用 `admin` 重新登录验收）。
- 本地 npm 启动器存在 `npm-prefix.js` 缺失问题，构建改用 `node D:\Node\node_modules\npm\bin\npm-cli.js run build`。
- 未执行的操作：删除/重置任何用户密码、修改既有工单状态之外的批量数据。

## 8. 结论

Phase 4 通过验收：人员管理、技能体系、真实负载统计、可解释规则调度、推荐与分配分离、调度审计均已落地，并通过单元测试、接口冒烟与浏览器逐页验证。

# 阶段 10：AI 模型管理与调用治理

## 1. 目标与边界

在既有「Java 业务后端 → Python AI 服务 → DeepSeek」链路上补齐生产可用的模型治理能力：

- 供应商与模型目录：新增、编辑、启停、验证、全局默认模型。
- 场景路由：6 个既有分析场景（工单分析、优先级、处置方案、知识推荐、调度辅助、工单问答）分别指定主/备模型与允许参数。
- 密钥治理：API Key 只写不可读，服务端 AES-256-GCM 加密存储，任何接口只返回掩码。
- 调用治理：主备自动降级、熔断、超时与重试、按全局/部门/角色/用户/场景与日/月周期的调用配额、用量与成本统计、异常告警与审计。
- 数据脱敏：发送给模型前统一遮盖手机号、邮箱、身份证号、银行卡号、Bearer/API Key/JWT 形态。
- 权限：只有超级管理员可编辑供应商与密钥；普通管理员只能调整场景路由参数与已批准模型的启停。

边界与不变式：

- 不修改既有工单、调度、知识、人员与角色数据；只新增 5 张 AI 治理表。
- 治理配置未启用（路由未开启、密钥未配置、模型未验证）时，调用继续沿用 Python 容器内的 `DEEPSEEK_*` 环境变量，线上行为与上线前一致。
- Prompt、模型响应正文、密钥明文不进入日志、审计与任何 API 响应。

## 2. 变更文件清单

### 数据库

| 文件 | 说明 |
| --- | --- |
| `deploy/migrations/20261120-ai-governance.sql` | 新增 `ai_providers`、`ai_models`、`ai_scenario_routes`、`ai_call_logs`、`ai_quotas`，并写入 6 条默认关闭的场景路由 |

### 后端（`backend-java`）

| 文件 | 说明 |
| --- | --- |
| `intelligence/governance/model/AiGovernanceModels.java` | 供应商/模型/路由/配额读模型，密钥只在内部 `ProviderSecret` 中出现 |
| `intelligence/governance/repository/AiGovernanceRepository.java` | 治理配置读写 |
| `intelligence/governance/repository/AiCallLogRepository.java` | 调用指标只追加写入与聚合查询 |
| `intelligence/governance/security/AiSecretCipher.java` | AES-256-GCM 加解密与掩码 |
| `intelligence/governance/security/AiAccessPolicy.java` | 管理员/超级管理员权限边界 |
| `intelligence/governance/security/AiPiiMasker.java` | 发送前脱敏 |
| `intelligence/governance/service/AiRouteResolver.java` | 场景 → 供应商/模型/参数解析，含可用性校验 |
| `intelligence/governance/service/AiCircuitBreaker.java` | 连续失败熔断与半开探测 |
| `intelligence/governance/service/AiQuotaService.java` | 全局/部门/用户/场景配额 |
| `intelligence/governance/service/AiCallLogService.java` | 调用记录、Token/成本统计与近 1 小时失败告警 |
| `intelligence/governance/service/AiProviderProbeService.java` | 服务端最小化连接测试 |
| `intelligence/governance/service/AiGovernanceService.java` | 治理 CRUD、二次校验、审计 |
| `intelligence/governance/controller/AiGovernanceController.java` | `/api/admin/ai/**` |
| `intelligence/governance/dto/AiGovernanceDtos.java` | 写请求与校验规则 |
| `intelligence/governance/vo/AiGovernanceResponses.java` | 测试结果、统计与调用记录响应 |
| `intelligence/service/AiAnalysisService.java` | 接入脱敏、配额、路由、降级、熔断、调用记录与失败审计 |
| `src/main/resources/application.yml` | `ai.governance.secret-key`、`security.super-admin-usernames` |

### Python AI 服务（`ai-python`）

| 文件 | 说明 |
| --- | --- |
| `app/main.py` | 请求体新增可选 `provider`，健康检查支持治理路由模式 |
| `app/llm.py` | 供应商覆盖参数、用量解析，密钥仍不落日志 |
| `app/service.py` | 透传供应商覆盖参数并返回 `usage` |
| `app/config.py` | 新增 `AI_ALLOW_PROVIDER_OVERRIDE` |

### 前端（`frontend`）

| 文件 | 说明 |
| --- | --- |
| `src/api/aiAdmin.ts` | AI 治理 API |
| `src/views/SystemAiView.vue` | 调用统计、供应商、模型、场景路由、配额五个页签 |
| `src/constants/features.ts`、`src/constants/menu.ts`、`src/router/index.ts` | 功能开关、仅管理员菜单、路由注册 |
| `tests/navigation-consistency.test.mjs` | 增加 AI 管理页与管理员可见性断言 |

### 部署与示例

| 文件 | 说明 |
| --- | --- |
| `docker-compose.yml` | migrate 执行新迁移；backend 注入 `AI_SECRET_KEY`、`SUPER_ADMIN_USERNAMES`；ai 注入 `AI_ALLOW_PROVIDER_OVERRIDE` |
| `.env.example`、`deploy/.env.example` | 新增变量占位 |

## 3. 设计说明

### 3.1 数据模型

- `ai_providers`：供应商编码/名称/Base URL、`api_key_cipher`（密文）、`api_key_mask`（掩码）、启停、最近测试结果与审计人。
- `ai_models`：所属供应商、模型编码、启停、`verified`（连接测试通过）、`is_default`、默认温度/最大输出/超时/每千 Token 成本。
- `ai_scenario_routes`：每个场景一行，主/备模型、可覆盖参数、最大重试次数与启用开关；默认全部关闭。
- `ai_call_logs`：场景、供应商/模型、尝试次数、状态、错误码、耗时、Token、成本、调用人与部门。不含 Prompt、响应正文、密钥。
- `ai_quotas`：`GLOBAL/DEPARTMENT/ROLE/USER/SCENARIO` × `DAY/MONTH` 的调用次数上限。

### 3.2 密钥生命周期

1. 主密钥 `AI_SECRET_KEY`（base64 编码的 32 字节）只从部署环境变量注入，仓库与文档中不出现真实值。
2. 写入时 `AES/GCM/NoPadding`，随机 12 字节 IV，输出 `base64(iv || ciphertext)`；同一明文每次密文不同。
3. 读取：仅 `AiRouteResolver` 与连接测试在内存中解密；API 只返回 `keyMask` 与 `hasKey`。
4. 未配置主密钥时：供应商密钥写入/启用被拒绝，治理路由自动回退到环境变量配置，不影响现有调用。

### 3.3 调用链

```text
前端 /api/ai/**（会话 + CSRF）
  → AiAnalysisService：权限校验 → 去重 → 配额 → 脱敏
  → AiRouteResolver：路由启用？供应商启用且有密钥？模型启用且已验证？
      否 → 沿用 Python 环境变量配置
      是 → 主模型调用（失败 → 熔断计数 → 备用模型 → 仍失败则返回错误）
  → AiCallLogService：记录状态/耗时/Token/成本
  → 失败时写审计（同场景+错误码 60 秒去重）
```

### 3.4 权限模型

- 读取治理配置、测试连接、调整路由与模型参数：`ADMIN`。
- 新增/编辑供应商与密钥、创建模型、设默认模型、维护配额：超级管理员。
- 超级管理员定义：`ADMIN` 且用户名命中 `SUPER_ADMIN_USERNAMES`；该变量留空时所有 `ADMIN` 视为超级管理员（兼容当前角色模型，待后续引入 `SUPER_ADMIN` 角色再收紧）。

### 3.5 审计

新增动作：`AI_PROVIDER_CREATE/UPDATE/KEY_UPDATE/ENABLE/DISABLE/TEST_SUCCESS/TEST_FAILURE`、`AI_MODEL_CREATE/UPDATE/ENABLE/DISABLE/VERIFY_SUCCESS/VERIFY_FAILURE/SET_DEFAULT`、`AI_ROUTE_UPDATE`、`AI_QUOTA_UPDATE/DELETE`、`AI_CALL_FAILURE`。审计只含编码、名称、模型名与掩码，不含密钥明文。

## 4. 部署步骤

1. 备份数据库（沿用每日 3:00 备份，或手动执行一次 `mysqldump`）。
2. 生成主密钥并写入服务器 `.env`（不要提交到仓库）：

```bash
cd /opt/serviceflow-ai
openssl rand -base64 32
# 将输出写入 .env：AI_SECRET_KEY=<上一步输出>
# 可选：SUPER_ADMIN_USERNAMES=admin
```

3. 确认 `AI_ALLOW_PROVIDER_OVERRIDE=true`（docker-compose 默认值）。
4. 应用迁移并重建后端/前端：

```bash
docker compose run --rm migrate
docker compose up -d --build backend frontend ai
```

5. 验证：

```bash
docker compose ps
curl -fsS http://127.0.0.1:8085/api/health/ready
docker compose logs --tail=80 backend
```

6. 管理端配置顺序：新增供应商 → 连接测试（成功）→ 模型自动标记已验证 → 启用模型 → 设置默认/场景路由 → 按需配置配额。

## 5. 回滚方案

1. 首选「配置回滚」：在管理页关闭场景路由（或 `UPDATE ai_scenario_routes SET enabled = 0`），调用立即回退到环境变量配置，无需回退代码。
2. 代码回滚：恢复上一版本 backend/frontend/ai 镜像或源码后 `docker compose up -d --build backend frontend ai`。
3. 数据回滚：迁移只新增表，不回滚也不影响既有业务；如需彻底移除，可先导出 `ai_call_logs` 留存，再按 `ai_quotas → ai_scenario_routes → ai_models → ai_providers → ai_call_logs` 顺序删除表。
4. 主密钥丢失时无法解密旧密文：重新为每个供应商设置一次 API Key 即可恢复，其他配置不受影响。

## 6. 验证结果

| 项目 | 结果 |
| --- | --- |
| 后端单元测试（含新增治理用例） | 233 个通过，0 失败 |
| 前端导航一致性测试 | 通过（含 AI 管理页与仅管理员断言） |
| 前端 `vue-tsc` + `vite build` | 通过 |
| Python 语法检查 | 通过 |
| 仓库敏感信息扫描 | 通过 |
| 密钥不可回读 | 单测断言：响应与审计均不含明文；只保存密文与掩码 |
| 脱敏 | 手机号/邮箱/身份证/银行卡/Bearer/API Key/JWT 均被替换 |
| 路由可用性 | 路由关闭、供应商停用、模型未验证、主密钥缺失均回退环境变量配置 |
| 配额 | 达到上限返回 `AI_QUOTA_EXCEEDED`（HTTP 429），停用配额不生效 |
| 越权 | 非管理员访问治理配置抛 `ForbiddenException`；普通管理员不能创建供应商或改密钥 |

## 7. 风险与限制

- HTTPS 仍未启用（服务器只有 IP）：会话 Cookie `Secure=false`，治理页与登录流量在公网为明文传输，需尽快补充域名与证书。
- 成本统计依赖手工维护的「每千 Token 成本」，不同供应商计价差异需按实际账单校准；未配置成本时只统计 Token。
- 配额按 `ai_call_logs` 计数，属最终一致的近似控制；流量突增瞬间可能略超额。
- 超级管理员当前用白名单实现，尚未引入独立角色；后续新增 `SUPER_ADMIN` 角色时可替换 `AiAccessPolicy`。
- 连接测试直接由 Java 服务端访问供应商 Base URL，属于受控出站请求；仅超级管理员可配置，需在安全组与网络策略上限制后端出站范围。

## 8. 未完成事项

- HTTPS、443、HTTP→HTTPS 跳转、HSTS 与 `COOKIE_SECURE=true`（等待域名与证书）。
- 审计导出、保留周期与归档策略。
- 后端聚合接口与大数据量仪表盘性能验证、全量 RBAC 越权自动化测试。
- 统一的管理类写操作二次确认组件（当前 AI 管理页与用户管理页各自实现确认弹窗）。

## 9. 生产部署记录（2026-11-18）

部署环境：ECS `120.26.150.85`，`/opt/serviceflow-ai`，HTTP 8085。

1. 备份：`/opt/serviceflow-backups/src-before-ai-gov-<时间戳>.tgz` 与 `db-before-ai-gov-<时间戳>.sql.gz`（已 `gzip -t` 校验）。
2. 主密钥：服务器内 `openssl rand -base64 32` 生成后直接追加到 `.env` 的 `AI_SECRET_KEY`，未在任何对话、日志或文档中回显。
3. 迁移：`docker compose run --rm migrate` 退出码 0；7 张 `ai_*` 治理表就绪（`ai_scenario_routes` 6 行默认关闭），审计触发器 `trg_audit_logs_no_update/no_delete` 存在。
4. 重建：`docker compose up -d --build backend frontend ai` 退出码 0，四个容器均 `healthy`，前端产物包含 `SystemAiView-*.js/css`。
5. 运行时：`/api/health/ready` 为 `UP`（mysql、ai 均 UP）；nginx 返回 `X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`、`Permissions-Policy`、`CSP`。
6. 登录态：admin 首次登录 `passwordChangeRequired=true`，改密前访问 `/api/admin/ai/meta` 返回 403「请先修改初始密码」；改密后返回 200 `{"keyConfigured":true,"superAdmin":true}`，`/providers` 200 空列表，`/routes` 返回 6 条。
7. 越权：AGENT 账号改密后访问 `/api/admin/ai/meta` 返回 403「仅管理员可访问 AI 治理配置」，`/api/admin/users` 同样 403；未登录访问治理接口返回 401。
8. 凭据处置：admin 与 AGENT 测试账号按用户指定的强密码重置（24 位混合字母数字），仅落在服务器 `/root/serviceflow-{admin,bob}-password.txt`（权限 600）；`token_version` 递增使旧会话立即失效。
9. 仍待完成：用真实供应商 API Key 走完「新增供应商 → 测试 → 模型验证 → 启用 → 路由 → 调用指标」的端到端链路（本轮无真实 Key，未在生产写入测试供应商）。
10. 越权返回码修正：新增 `AdminInterceptor` 统一拦截 `/api/admin/**`，在参数绑定与 Bean Validation 之前判定角色。修正前非管理员 POST 空 body 会先得到 400（暴露入参结构），修正后统一 403；已补 `AdminInterceptorTest`（ADMIN 放行、AGENT/USER 拒绝、OPTIONS 放行）并重新部署验证。

## 10. 在线测试记录（2026-11-19）

测试账号（保留供后续在线测试，密码仅存服务器 `/root/serviceflow-qa-accounts.txt`，权限 600）：`qa.agent01`、`qa.agent02`（AGENT）、`qa.user01`、`qa.user02`（USER）。

### 10.1 模块功能
- 用户管理：创建 201、重复用户名 400、弱口令 400、修改资料 200、重置密码 200 且强制改密、停用后无法登录、重新启用可登录；响应与审计均不含明文密码。
- 工单：无 CSRF 写入 403；创建 201、非法类型 400、空标题 400；创建人分派 → 处理人受理/开始/解决 → 创建人关闭全部 200；处理人执行关闭 403；USER 删除工单 403；事件流有记录。
- 只读巡检（AGENT）：知识库、SLA 策略、坐席负载、分析报表均 200；派单接口 403（管理员专用，符合设计）。
- AI：未配置模型密钥时返回 503「AI 服务未配置模型密钥」（清晰降级，非 500）；配额新增 200、超限 429、删除 200。

### 10.2 多来源 IP 并发（同一时间窗）
| 来源 | 请求 | 结果 |
| --- | --- | --- |
| 127.0.0.1 | 30 次 / 并发 15 | 30×200，2415 ms |
| 172.17.0.1 | 30 次 / 并发 15 | 30×200，996 ms |
| 笔记本公网出口 | 20 次 / 并发 20 | 20×200，4243 ms（含进程启动开销） |

审计 `audit_logs.ip` 分别记录 `172.18.0.1`、`172.18.0.3`、`223.160.144.121` 三类来源；伪造请求头 `X-Forwarded-For` / `X-Real-IP` 未被采信（nginx 只透传真实 `$remote_addr`）。

### 10.3 安全校验
- 登录限流：连续错误 5 次后开始返回 429；数据库锁定生效，测试后已清零。
- 会话：登出后旧会话 401；会话 Cookie 为 `HttpOnly; SameSite=Lax`（HTTP 过渡期无 `Secure`）。
- 未登录访问管理接口 401，AGENT 访问 403。

### 10.4 本轮发现并修复
- 非管理员对管理接口 POST 返回 400 → 403（`AdminInterceptor`）。
- 非法 JSON 请求体返回 500 → 400（`HttpMessageNotReadableException`）。
- 未知接口路径返回 500 → 404（`NoResourceFoundException`），未登录时仍为 401。

### 10.5 未完成
- 生产未配置 `DEEPSEEK_API_KEY`，AI 分析链路无法完成真实调用；配置真实供应商密钥后需复测「分析 → ai_call_logs → 调用统计」。

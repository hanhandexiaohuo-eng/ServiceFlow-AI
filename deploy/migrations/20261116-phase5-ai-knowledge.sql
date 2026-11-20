-- Phase 5：AI 智能工单处理中心 + 知识库（MySQL 8.x，可重复执行）
-- 只新增表与知识种子，不修改 users / tickets / dispatch 等既有结构，无破坏性变更。
-- 执行方式：mysql -u <user> -p serviceflow < deploy/migrations/20261116-phase5-ai-knowledge.sql
USE `serviceflow`;

-- 1) AI 会话：与工单关联、归属到具体用户
CREATE TABLE IF NOT EXISTS `ai_sessions` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`  BIGINT UNSIGNED NOT NULL,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `title`      VARCHAR(120) NOT NULL,
  `status`     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_sessions_user` (`user_id`, `updated_at`),
  KEY `idx_ai_sessions_ticket` (`ticket_id`, `id`),
  CONSTRAINT `fk_ai_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ai_sessions_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 会话';

-- 2) AI 分析记录：先落 PENDING，再按调用结果更新为 SUCCESS / FAILED
CREATE TABLE IF NOT EXISTS `ai_analyses` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`       BIGINT UNSIGNED NOT NULL,
  `session_id`      BIGINT UNSIGNED NULL,
  `analysis_type`   VARCHAR(32) NOT NULL COMMENT 'TICKET_ANALYSIS / PRIORITY / SOLUTION / DISPATCH / CHAT',
  `status`          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SUCCESS / FAILED',
  `requested_by`    BIGINT UNSIGNED NOT NULL,
  `request_note`    VARCHAR(500) NULL,
  `prompt_version`  VARCHAR(64) NULL,
  `model`           VARCHAR(64) NULL,
  `latency_ms`      INT NULL,
  `ticket_snapshot` JSON NULL COMMENT '分析时的工单与 SLA 快照',
  `result_json`     JSON NULL COMMENT '已通过 Schema 校验的结构化结果',
  `error_code`      VARCHAR(64) NULL,
  `error_message`   VARCHAR(500) NULL,
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed_at`    DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_analyses_ticket` (`ticket_id`, `id`),
  KEY `idx_ai_analyses_user` (`requested_by`, `id`),
  KEY `idx_ai_analyses_type` (`analysis_type`, `created_at`),
  CONSTRAINT `fk_ai_analyses_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ai_analyses_session` FOREIGN KEY (`session_id`) REFERENCES `ai_sessions` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 分析记录';

-- 3) AI 消息：会话内的用户提问与 AI 回复
CREATE TABLE IF NOT EXISTS `ai_messages` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id`  BIGINT UNSIGNED NOT NULL,
  `role`        VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM',
  `content`     TEXT NOT NULL,
  `analysis_id` BIGINT UNSIGNED NULL,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_messages_session` (`session_id`, `id`),
  CONSTRAINT `fk_ai_messages_session` FOREIGN KEY (`session_id`) REFERENCES `ai_sessions` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ai_messages_analysis` FOREIGN KEY (`analysis_id`) REFERENCES `ai_analyses` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 会话消息';

-- 4) AI 建议：必须经人工确认后才可能触达业务
CREATE TABLE IF NOT EXISTS `ai_suggestions` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `analysis_id`     BIGINT UNSIGNED NOT NULL,
  `ticket_id`       BIGINT UNSIGNED NOT NULL,
  `suggestion_type` VARCHAR(32) NOT NULL COMMENT 'CLASSIFICATION / PRIORITY / SOLUTION',
  `status`          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACCEPTED / REJECTED / EXECUTED',
  `payload`         JSON NOT NULL,
  `summary`         VARCHAR(500) NOT NULL,
  `created_by`      BIGINT UNSIGNED NOT NULL,
  `decided_by`      BIGINT UNSIGNED NULL,
  `decided_at`      DATETIME NULL,
  `decision_note`   VARCHAR(500) NULL,
  `execution_note`  VARCHAR(500) NULL,
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_suggestion_analysis_type` (`analysis_id`, `suggestion_type`),
  KEY `idx_ai_suggestions_ticket` (`ticket_id`, `status`),
  CONSTRAINT `fk_ai_suggestions_analysis` FOREIGN KEY (`analysis_id`) REFERENCES `ai_analyses` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ai_suggestions_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 建议与人工确认记录';

-- 5) 知识库文章：第一版为关键词检索，不使用向量库
CREATE TABLE IF NOT EXISTS `kb_articles` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title`            VARCHAR(200) NOT NULL,
  `category`         VARCHAR(32) NOT NULL COMMENT 'NETWORK / SYSTEM / DATABASE / SECURITY / SOFTWARE / ACCOUNT / HARDWARE / OTHER',
  `problem`          TEXT NOT NULL,
  `solution`         MEDIUMTEXT NOT NULL,
  `tech_stack`       VARCHAR(200) NULL,
  `keywords`         VARCHAR(255) NOT NULL COMMENT '空格分隔关键词',
  `source_ticket_id` BIGINT UNSIGNED NULL,
  `status`           VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED / ARCHIVED',
  `created_by`       BIGINT UNSIGNED NULL,
  `updated_by`       BIGINT UNSIGNED NULL,
  `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_articles_title` (`title`),
  KEY `idx_kb_articles_category` (`category`),
  KEY `idx_kb_articles_updated` (`updated_at`),
  CONSTRAINT `fk_kb_articles_source_ticket` FOREIGN KEY (`source_ticket_id`) REFERENCES `tickets` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_kb_articles_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文章';

-- 6) 知识与工单关联
CREATE TABLE IF NOT EXISTS `kb_ticket_links` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `article_id` BIGINT UNSIGNED NOT NULL,
  `ticket_id`  BIGINT UNSIGNED NOT NULL,
  `linked_by`  BIGINT UNSIGNED NOT NULL,
  `note`       VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_ticket_link` (`article_id`, `ticket_id`),
  KEY `idx_kb_ticket_links_ticket` (`ticket_id`),
  CONSTRAINT `fk_kb_ticket_links_article` FOREIGN KEY (`article_id`) REFERENCES `kb_articles` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_kb_ticket_links_ticket` FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识与工单关联';

-- 7) 初始知识内容（真实可检索的记录，非业务统计伪造；INSERT IGNORE 保证可重复执行）
INSERT IGNORE INTO `kb_articles` (`title`, `category`, `problem`, `solution`, `tech_stack`, `keywords`) VALUES
('办公网无法访问外网的分层排查', 'NETWORK',
 '办公终端可以访问内网系统，但浏览器打开外部网站超时，其他同事正常。',
 '1. 确认故障范围：让同网段同事访问同一站点，排除目标站点故障。\n2. 检查本机代理：Windows 设置 > 网络和 Internet > 代理，确认未残留失效代理；浏览器单独检查代理插件。\n3. 检查 DNS：ipconfig /flushdns 后 nslookup www.example.com，若解析失败改用公司 DNS 再测。\n4. 检查网关与路由：ping 网关、tracert 目标域名，定位在接入层还是出口层中断。\n5. 检查安全策略：确认终端未命中上网行为管理的阻断策略。\n6. 仍失败时收集 tracert 结果与时间点，升级网络组。',
 'Windows 10/11、DNS、企业代理', '外网 无法访问 代理 DNS tracert 网络'),

('VPN 连接失败的常见原因与处理', 'NETWORK',
 '远程办公时 VPN 客户端提示连接超时或认证失败，无法进入内网。',
 '1. 认证失败：核对账号是否过期、是否被锁定；连续失败后等待锁定窗口结束或联系管理员解锁。\n2. 连接超时：确认本地网络可访问 VPN 网关端口（默认 443/UDP 500），公司网络策略是否放行。\n3. 客户端异常：退出客户端后重启服务，仍失败则卸载重装并清空旧配置。\n4. 证书问题：检查系统时间是否准确，证书过期会导致握手失败。\n5. 多网卡/代理冲突：关闭第三方代理与虚拟网卡后重试。\n6. 收集客户端日志中的错误码，按错误码升级。',
 'VPN 客户端、TLS、Windows', 'VPN 连接失败 认证 超时 证书'),

('账号被锁定的解锁与自查', 'ACCOUNT',
 '登录业务系统提示账号已锁定或密码错误次数过多。',
 '1. 确认是否本人多次输错密码，等待系统锁定窗口自动解除（通常 15-30 分钟）。\n2. 检查是否有旧设备、手机邮件客户端仍在使用旧密码反复认证，先退出这些客户端。\n3. 联系管理员在后台解锁，并核对账号状态与有效期。\n4. 解锁后立即修改密码，避免使用历史密码。\n5. 若短时间内再次锁定，按"凭据泄露"处理：修改密码、检查登录日志、必要时禁用会话。',
 'AD/LDAP、统一认证', '账号锁定 密码错误 解锁 认证'),

('打印机显示脱机无法打印的处理', 'HARDWARE',
 '打印任务进入队列后不输出，打印机状态显示脱机或错误。',
 '1. 检查打印机电源、网线/Wi-Fi 指示灯，打印自检页确认设备本身正常。\n2. 在系统"打印机和扫描仪"中取消"脱机使用打印机"选项。\n3. 清空打印队列：停止 Print Spooler 服务，删除 C:\\Windows\\System32\\spool\\PRINTERS 下残留文件，再启动服务。\n4. 重新添加打印机，优先使用固定 IP 端口而不是 WSD 自动发现。\n5. 换一台电脑测试，判断是终端驱动问题还是打印机网络问题。\n6. 仍失败时记录打印机 IP 与错误灯状态，升级硬件支持。',
 'Windows Print Spooler、网络打印机', '打印机 脱机 打印队列 Spooler 驱动'),

('数据库连接池耗尽的排查路径', 'DATABASE',
 '应用偶发超时，日志出现获取连接超时或连接池已满。',
 '1. 查看连接池指标：活跃连接、空闲连接、等待线程数与等待时长。\n2. 查询数据库当前会话：SHOW PROCESSLIST，按执行时间排序找出长事务与慢 SQL。\n3. 检查是否有未提交事务或连接未关闭的代码路径。\n4. 对慢 SQL 执行 EXPLAIN，确认索引命中与扫描行数。\n5. 临时扩容连接池只能缓解，必须同时修复慢查询或泄漏点。\n6. 观察修复后 30 分钟连接曲线是否回落。',
 'MySQL 8、HikariCP、JdbcTemplate', '数据库 连接池 超时 慢SQL 事务'),

('终端疑似勒索病毒的隔离与上报', 'SECURITY',
 '安全软件提示发现可疑加密行为，文件扩展名被批量修改，终端可能已断网。',
 '1. 立即物理断网或关闭 Wi-Fi，保留现场，不要直接关机或格式化。\n2. 记录告警截图、被加密文件样例路径与时间点，不要打开可疑附件。\n3. 使用安全软件执行全盘查杀，隔离检出样本，不自行删除以免破坏取证。\n4. 排查同网段共享目录、映射盘是否出现同类加密文件，评估横向扩散。\n5. 通知安全负责人并按事件流程上报，必要时断开相关共享账号。\n6. 确认清理完成后再恢复网络，优先从备份恢复数据并验证完整性。',
 'EDR、Windows 共享、备份', '勒索病毒 加密 隔离 取证 安全事件');

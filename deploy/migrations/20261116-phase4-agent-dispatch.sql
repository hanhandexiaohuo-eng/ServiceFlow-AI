-- Phase 4：智能调度 + 人员负载数据库迁移（MySQL 8.x，可重复执行）
-- 只新增表与种子数据，不修改 users / tickets 现有结构，无破坏性变更。
-- 执行方式：mysql -u <user> -p serviceflow < deploy/migrations/20261116-phase4-agent-dispatch.sql
USE `serviceflow`;

-- 1) 人员档案：user_id 即人员标识，主键与 users.id 一致
CREATE TABLE IF NOT EXISTS `agent_profiles` (
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '用户 id，同时作为人员 id',
  `max_concurrent` INT NOT NULL DEFAULT 5 COMMENT '最大并发处理工单数',
  `availability`  VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
                  COMMENT '人员工作状态：AVAILABLE/BUSY/OFFLINE/DISABLED',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  KEY `idx_agent_profiles_availability` (`availability`),
  CONSTRAINT `fk_agent_profiles_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='人员档案';

-- 2) 技能字典
CREATE TABLE IF NOT EXISTS `agent_skills` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `skill_code`  VARCHAR(32) NOT NULL COMMENT '技能编码，如 SYS_OPS',
  `skill_name`  VARCHAR(64) NOT NULL COMMENT '技能名称',
  `description` VARCHAR(255) NULL COMMENT '技能说明',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skills_code` (`skill_code`),
  UNIQUE KEY `uk_agent_skills_name` (`skill_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='技能字典';

-- 3) 人员技能关系
CREATE TABLE IF NOT EXISTS `agent_skill_relations` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id`          BIGINT UNSIGNED NOT NULL COMMENT '人员 id = users.id',
  `skill_id`          BIGINT UNSIGNED NOT NULL,
  `proficiency_level` TINYINT NOT NULL COMMENT '熟练度：1初级 2中级 3高级 4专家',
  `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill_relation` (`agent_id`, `skill_id`),
  KEY `idx_agent_skill_relation_skill` (`skill_id`),
  CONSTRAINT `fk_agent_skill_relation_agent` FOREIGN KEY (`agent_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_skill_relation_skill` FOREIGN KEY (`skill_id`) REFERENCES `agent_skills` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='人员技能关系';

-- 4) 工单类型 → 技能规则（数据驱动，避免标题字符串匹配）
CREATE TABLE IF NOT EXISTS `ticket_type_skill_rules` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_type` VARCHAR(32) NOT NULL COMMENT 'INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT',
  `skill_id`    BIGINT UNSIGNED NOT NULL,
  `required`    TINYINT NOT NULL DEFAULT 1 COMMENT '1 必需，0 加分项',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_type_skill` (`ticket_type`, `skill_id`),
  KEY `idx_ticket_type_skill_type` (`ticket_type`),
  CONSTRAINT `fk_ticket_type_skill_skill` FOREIGN KEY (`skill_id`) REFERENCES `agent_skills` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工单类型技能规则';

-- 5) 调度日志：推荐与确认分配快照
CREATE TABLE IF NOT EXISTS `dispatch_logs` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`       BIGINT UNSIGNED NOT NULL,
  `ticket_no`       VARCHAR(32) NOT NULL,
  `action`          VARCHAR(16) NOT NULL COMMENT 'RECOMMEND / ASSIGN',
  `operator_id`     BIGINT UNSIGNED NULL,
  `operator_name`   VARCHAR(64) NOT NULL,
  `agent_id`        BIGINT UNSIGNED NULL,
  `agent_name`      VARCHAR(64) NULL,
  `score`           DECIMAL(5,1) NULL,
  `candidate_count` INT NULL,
  `note`            VARCHAR(500) NULL,
  `detail`          JSON NULL COMMENT '推荐候选快照',
  `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dispatch_logs_ticket` (`ticket_id`, `id`),
  KEY `idx_dispatch_logs_agent` (`agent_id`),
  KEY `idx_dispatch_logs_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='调度日志';

-- 6) 技能种子（INSERT IGNORE 保证可重复执行）
INSERT IGNORE INTO `agent_skills` (`skill_code`, `skill_name`, `description`) VALUES
  ('JAVA',             'Java 开发',     'Java 后端开发与问题排查'),
  ('DATABASE',         '数据库',        'MySQL 等数据库运维与优化'),
  ('NETWORK',          '网络运维',      '网络连接、VPN、防火墙排障'),
  ('SYS_OPS',          '系统运维',      '操作系统与业务系统运维'),
  ('FRONTEND',         '前端开发',      'Web 前端开发与兼容性问题'),
  ('SERVER',           '服务器管理',    '服务器、中间件与资源管理'),
  ('SOFTWARE_SUPPORT', '软件使用支持',  '办公软件与客户端使用支持'),
  ('OTHER',            '其他技能',      '其他自定义技能');

-- 7) 类型—技能规则种子（按技能编码关联，避免硬编码自增 id）
INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'INCIDENT', s.`id`, 1 FROM `agent_skills` s
WHERE s.`skill_code` = 'SYS_OPS'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'INCIDENT' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'INCIDENT', s.`id`, 0 FROM `agent_skills` s
WHERE s.`skill_code` = 'NETWORK'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'INCIDENT' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'SERVICE_REQUEST', s.`id`, 1 FROM `agent_skills` s
WHERE s.`skill_code` = 'SOFTWARE_SUPPORT'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'SERVICE_REQUEST' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'SERVICE_REQUEST', s.`id`, 0 FROM `agent_skills` s
WHERE s.`skill_code` = 'SERVER'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'SERVICE_REQUEST' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'SERVICE_REQUEST', s.`id`, 0 FROM `agent_skills` s
WHERE s.`skill_code` = 'FRONTEND'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'SERVICE_REQUEST' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'CHANGE', s.`id`, 1 FROM `agent_skills` s
WHERE s.`skill_code` = 'SERVER'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'CHANGE' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'CHANGE', s.`id`, 0 FROM `agent_skills` s
WHERE s.`skill_code` = 'DATABASE'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'CHANGE' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'CHANGE', s.`id`, 0 FROM `agent_skills` s
WHERE s.`skill_code` = 'SYS_OPS'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'CHANGE' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'CHANGE', s.`id`, 0 FROM `agent_skills` s
WHERE s.`skill_code` = 'JAVA'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'CHANGE' AND r.`skill_id` = s.`id`);

INSERT INTO `ticket_type_skill_rules` (`ticket_type`, `skill_id`, `required`)
SELECT 'CONSULT', s.`id`, 1 FROM `agent_skills` s
WHERE s.`skill_code` = 'SOFTWARE_SUPPORT'
  AND NOT EXISTS (SELECT 1 FROM `ticket_type_skill_rules` r WHERE r.`ticket_type` = 'CONSULT' AND r.`skill_id` = s.`id`);

-- 8) 迁移结果核对
SELECT 'agent_skills' AS metric, COUNT(*) AS value FROM `agent_skills`
UNION ALL SELECT 'ticket_type_skill_rules', COUNT(*) FROM `ticket_type_skill_rules`
UNION ALL SELECT 'agent_profiles', COUNT(*) FROM `agent_profiles`
UNION ALL SELECT 'dispatch_logs', COUNT(*) FROM `dispatch_logs`;

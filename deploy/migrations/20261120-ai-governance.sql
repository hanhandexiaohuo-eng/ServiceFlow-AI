-- 2026-11-20 AI 模型管理与调用治理（MySQL 8.x，可重复执行）
-- 只新增表与场景占位行，不修改任何既有业务数据。
-- API Key 仅保存 AES-GCM 密文与掩码，不保存明文；密文主密钥来自部署环境变量 AI_SECRET_KEY。

USE `serviceflow`;

CREATE TABLE IF NOT EXISTS `ai_providers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `provider_code` VARCHAR(32) NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  `base_url` VARCHAR(255) NOT NULL,
  `api_key_cipher` TEXT NULL COMMENT 'AES-GCM 密文，只写不可回读',
  `api_key_mask` VARCHAR(64) NULL COMMENT '掩码展示，例如 sk-...abcd',
  `api_key_updated_at` DATETIME NULL,
  `enabled` TINYINT NOT NULL DEFAULT 0,
  `last_test_at` DATETIME NULL,
  `last_test_result` VARCHAR(16) NULL COMMENT 'SUCCESS / FAILURE',
  `last_test_message` VARCHAR(255) NULL,
  `created_by` BIGINT NULL,
  `updated_by` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_providers_code` (`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 供应商配置';

CREATE TABLE IF NOT EXISTS `ai_models` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `provider_id` BIGINT NOT NULL,
  `model_code` VARCHAR(64) NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 0,
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '连接测试成功后才可置为已验证',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '全局默认模型，仅一个',
  `temperature` DECIMAL(3,2) NOT NULL DEFAULT 0.20,
  `max_tokens` INT NOT NULL DEFAULT 2048,
  `timeout_seconds` INT NOT NULL DEFAULT 60,
  `cost_per_1k_micros` BIGINT NOT NULL DEFAULT 0 COMMENT '每千 token 成本（微元，0 表示不计价）',
  `created_by` BIGINT NULL,
  `updated_by` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_models_provider_model` (`provider_id`, `model_code`),
  KEY `idx_ai_models_enabled` (`enabled`, `verified`),
  CONSTRAINT `fk_ai_models_provider` FOREIGN KEY (`provider_id`) REFERENCES `ai_providers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型目录';

CREATE TABLE IF NOT EXISTS `ai_scenario_routes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scenario` VARCHAR(32) NOT NULL COMMENT 'TICKET_ANALYSIS/PRIORITY/SOLUTION/KNOWLEDGE/DISPATCH/CHAT',
  `primary_model_id` BIGINT NULL,
  `fallback_model_id` BIGINT NULL,
  `temperature` DECIMAL(3,2) NULL COMMENT 'NULL 表示采用模型默认值',
  `max_tokens` INT NULL,
  `timeout_seconds` INT NULL,
  `max_retries` INT NOT NULL DEFAULT 1,
  `enabled` TINYINT NOT NULL DEFAULT 0,
  `updated_by` BIGINT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_scenario_routes_scenario` (`scenario`),
  CONSTRAINT `fk_ai_routes_primary` FOREIGN KEY (`primary_model_id`) REFERENCES `ai_models` (`id`),
  CONSTRAINT `fk_ai_routes_fallback` FOREIGN KEY (`fallback_model_id`) REFERENCES `ai_models` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='场景到模型的允许参数与主备路由';

CREATE TABLE IF NOT EXISTS `ai_call_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `analysis_id` BIGINT NULL,
  `scenario` VARCHAR(32) NOT NULL,
  `provider_id` BIGINT NULL,
  `model_id` BIGINT NULL,
  `model_name` VARCHAR(64) NULL,
  `attempt` TINYINT NOT NULL DEFAULT 1,
  `status` VARCHAR(16) NOT NULL COMMENT 'SUCCESS / FAILED',
  `error_code` VARCHAR(48) NULL,
  `latency_ms` INT NULL,
  `prompt_tokens` INT NULL,
  `completion_tokens` INT NULL,
  `total_tokens` INT NULL,
  `cost_micros` BIGINT NULL,
  `actor_id` BIGINT NULL,
  `actor_department` VARCHAR(64) NULL,
  `actor_role` VARCHAR(16) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_call_logs_created` (`created_at`),
  KEY `idx_ai_call_logs_scenario` (`scenario`, `created_at`),
  KEY `idx_ai_call_logs_actor` (`actor_id`, `created_at`),
  KEY `idx_ai_call_logs_role` (`actor_role`, `created_at`),
  KEY `idx_ai_call_logs_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用记录：只记录指标，不含 Prompt 与业务正文';

-- 兼容已执行过早期版本的环境：缺列时补 actor_role
SET @has_actor_role := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_call_logs' AND COLUMN_NAME = 'actor_role'
);
SET @ddl := IF(@has_actor_role = 0,
  'ALTER TABLE `ai_call_logs` ADD COLUMN `actor_role` VARCHAR(16) NULL AFTER `actor_department`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `ai_quotas` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scope_type` VARCHAR(16) NOT NULL COMMENT 'GLOBAL / DEPARTMENT / ROLE / USER / SCENARIO',
  `scope_value` VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'DEPARTMENT 名称 / USER id / SCENARIO 名称；GLOBAL 为空',
  `period` VARCHAR(8) NOT NULL DEFAULT 'DAY' COMMENT 'DAY / MONTH',
  `max_calls` INT NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `updated_by` BIGINT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_quotas_scope` (`scope_type`, `scope_value`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用配额';

-- 场景占位行：默认关闭，关闭时沿用环境变量中的既有配置，不影响当前线上行为
INSERT IGNORE INTO `ai_scenario_routes` (`scenario`, `max_retries`, `enabled`) VALUES
  ('TICKET_ANALYSIS', 1, 0),
  ('PRIORITY', 1, 0),
  ('SOLUTION', 1, 0),
  ('KNOWLEDGE', 1, 0),
  ('DISPATCH', 1, 0),
  ('CHAT', 1, 0);

-- 2026-11-18 安全加固迁移（MySQL 8.x，可重复执行）
-- 仅新增列与审计表，不删除/修改既有业务数据；users 表敏感列不落明文。
USE `serviceflow`;

-- 1) users：首次登录强制改密、会话版本、登录失败与锁定
SET @has = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'password_change_required');
SET @ddl = IF(@has = 0, 'ALTER TABLE `users` ADD COLUMN `password_change_required` TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'token_version');
SET @ddl = IF(@has = 0, 'ALTER TABLE `users` ADD COLUMN `token_version` INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'failed_login_count');
SET @ddl = IF(@has = 0, 'ALTER TABLE `users` ADD COLUMN `failed_login_count` INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'locked_until');
SET @ddl = IF(@has = 0, 'ALTER TABLE `users` ADD COLUMN `locked_until` DATETIME NULL', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'last_login_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `users` ADD COLUMN `last_login_at` DATETIME NULL', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 安全审计日志：仅追加，不含密码/Token/API Key 等凭据
CREATE TABLE IF NOT EXISTS `audit_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_id` BIGINT NULL,
  `actor_username` VARCHAR(64) NULL,
  `action` VARCHAR(64) NOT NULL,
  `object_type` VARCHAR(64) NULL,
  `object_id` VARCHAR(64) NULL,
  `detail` VARCHAR(512) NULL,
  `ip` VARCHAR(64) NULL,
  `request_id` VARCHAR(64) NULL,
  `result` VARCHAR(16) NOT NULL,
  `failure_reason` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_created` (`created_at`),
  KEY `idx_audit_actor` (`actor_id`),
  KEY `idx_audit_action` (`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 审计日志 append-only 触发器不在此执行：
--    开启 binlog 的 MySQL 要求创建者具备 SUPER 或 log_bin_trust_function_creators=1，
--    应用账号（MYSQL_USER）无该权限，放进普通迁移会让 migrate 任务整体失败。
--    触发器定义见 20261118-audit-append-only.sql：
--      - 新库：由 deploy/init/06-audit-append-only.sql 在初始化阶段以 root 执行；
--      - 已有库：由 DBA 以 root 执行 20261118-audit-append-only.sql（见部署文档）。


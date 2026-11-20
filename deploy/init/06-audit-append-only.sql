-- 2026-11-18 审计日志表与 append-only 触发器（初始化阶段以 root 执行，可重复执行）
-- 新库初始化时 root 具备 SUPER，可创建触发器；已有库见 deploy/migrations/20261118-audit-append-only.sql
USE `serviceflow`;

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

DROP TRIGGER IF EXISTS `trg_audit_logs_no_update`;
CREATE TRIGGER `trg_audit_logs_no_update` BEFORE UPDATE ON `audit_logs`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

DROP TRIGGER IF EXISTS `trg_audit_logs_no_delete`;
CREATE TRIGGER `trg_audit_logs_no_delete` BEFORE DELETE ON `audit_logs`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

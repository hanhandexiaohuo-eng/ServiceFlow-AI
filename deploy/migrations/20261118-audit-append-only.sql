-- 2026-11-18 审计日志 append-only 触发器（需 root / SUPER 权限执行，可重复执行）
-- 普通应用账号无法在开启 binlog 的 MySQL 上创建触发器，因此单独成文件：
--   新库：由 deploy/init/06-audit-append-only.sql 在初始化阶段自动执行；
--   已有库：docker exec -i serviceflow-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--             "$MYSQL_DATABASE" < deploy/migrations/20261118-audit-append-only.sql
USE `serviceflow`;

-- 表可能尚未创建（全新卷且未跑过 20261118-security-hardening.sql）
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

-- 数据库层强制审计日志只追加：任何 UPDATE/DELETE 直接报错（含误操作与注入）
DROP TRIGGER IF EXISTS `trg_audit_logs_no_update`;
CREATE TRIGGER `trg_audit_logs_no_update` BEFORE UPDATE ON `audit_logs`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

DROP TRIGGER IF EXISTS `trg_audit_logs_no_delete`;
CREATE TRIGGER `trg_audit_logs_no_delete` BEFORE DELETE ON `audit_logs`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

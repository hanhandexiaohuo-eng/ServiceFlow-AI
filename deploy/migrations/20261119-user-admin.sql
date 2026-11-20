-- 2026-11-19 用户管理：部门归属（MySQL 8.x，可重复执行）
-- 仅新增列，不修改既有用户数据；角色仍复用 users.role（ADMIN / AGENT / USER）。
USE `serviceflow`;

SET @has = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'department');
SET @ddl = IF(@has = 0, 'ALTER TABLE `users` ADD COLUMN `department` VARCHAR(64) NULL', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 审计查询常用过滤：结果 + 时间范围（created_at 已有索引）
SET @has = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'audit_logs' AND index_name = 'idx_audit_result');
SET @ddl = IF(@has = 0, 'ALTER TABLE `audit_logs` ADD INDEX `idx_audit_result` (`result`, `created_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

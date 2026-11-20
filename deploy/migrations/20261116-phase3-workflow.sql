-- Phase 3 工作流 + SLA 数据库迁移（MySQL 8.x，可重复执行，无需 CREATE ROUTINE 权限）
-- 执行方式：mysql -u <user> -p serviceflow < deploy/migrations/20261116-phase3-workflow.sql
USE `serviceflow`;

-- 1) 迁移前快照：只在第一次执行时创建，不覆盖已有备份
CREATE TABLE IF NOT EXISTS `tickets_phase2_backup` AS SELECT * FROM `tickets`;

-- 2) 幂等加列（MySQL 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema + PREPARE 判断）
SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'creator_id');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `creator_id` BIGINT UNSIGNED NULL COMMENT ''创建人用户 id'' AFTER `creator`', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'assignee_id');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `assignee_id` BIGINT UNSIGNED NULL COMMENT ''处理人用户 id'' AFTER `assignee`', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'sla_due_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `sla_due_at` DATETIME NULL COMMENT ''SLA 响应截止时间''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'first_response_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `first_response_at` DATETIME NULL COMMENT ''首次接单时间''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'resolved_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `resolved_at` DATETIME NULL COMMENT ''解决时间''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'closed_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `closed_at` DATETIME NULL COMMENT ''关闭时间''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'cancelled_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `cancelled_at` DATETIME NULL COMMENT ''取消时间''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'resolution');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `resolution` TEXT NULL COMMENT ''解决说明''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'tickets' AND column_name = 'cancel_reason');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD COLUMN `cancel_reason` VARCHAR(500) NULL COMMENT ''取消原因''', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) 幂等加索引
SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'tickets' AND index_name = 'idx_tickets_sla_due_at');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD INDEX `idx_tickets_sla_due_at` (`sla_due_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'tickets' AND index_name = 'idx_tickets_assignee_id');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD INDEX `idx_tickets_assignee_id` (`assignee_id`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 事件表
CREATE TABLE IF NOT EXISTS `ticket_events` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`     BIGINT UNSIGNED NOT NULL,
  `action`        VARCHAR(24)  NOT NULL,
  `from_status`   VARCHAR(16)  NULL,
  `to_status`     VARCHAR(16)  NULL,
  `operator_id`   BIGINT UNSIGNED NULL,
  `operator_name` VARCHAR(64)  NOT NULL,
  `note`          VARCHAR(500) NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ticket_events_ticket` (`ticket_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工单流转事件表';

-- 5) 旧状态映射：OPEN 按是否有处理人拆成 PENDING / ASSIGNED
UPDATE `tickets`
SET `status` = 'ASSIGNED'
WHERE `status` = 'OPEN' AND `assignee` IS NOT NULL AND TRIM(`assignee`) <> '';

UPDATE `tickets` SET `status` = 'PENDING'    WHERE `status` = 'OPEN';
UPDATE `tickets` SET `status` = 'PROCESSING' WHERE `status` = 'IN_PROGRESS';

-- 6) SLA 截止时间回填（与 Java SlaPolicy 保持一致：4/8/24/72 小时）
UPDATE `tickets`
SET `sla_due_at` = DATE_ADD(
      `created_at`,
      INTERVAL CASE `priority`
        WHEN 'URGENT' THEN 4
        WHEN 'HIGH'   THEN 8
        WHEN 'MEDIUM' THEN 24
        ELSE 72
      END HOUR
    )
WHERE `sla_due_at` IS NULL;

-- 7) 终态时间戳回填（历史数据只有 updated_at，作为近似值）
UPDATE `tickets` SET `resolved_at` = `updated_at` WHERE `status` = 'RESOLVED' AND `resolved_at` IS NULL;
UPDATE `tickets` SET `closed_at`   = `updated_at` WHERE `status` = 'CLOSED'   AND `closed_at` IS NULL;

-- 8) 按 users.name 回填人员 id；匹配不到的历史自由文本保持 NULL
UPDATE `tickets` t JOIN `users` u ON t.`creator` = u.`name`
SET t.`creator_id` = u.`id`
WHERE t.`creator_id` IS NULL;

UPDATE `tickets` t JOIN `users` u ON t.`assignee` = u.`name`
SET t.`assignee_id` = u.`id`
WHERE t.`assignee_id` IS NULL AND t.`assignee` IS NOT NULL AND TRIM(t.`assignee`) <> '';

-- 9) 历史工单补 CREATE 事件（防重复）
INSERT INTO `ticket_events` (`ticket_id`, `action`, `from_status`, `to_status`, `operator_id`, `operator_name`, `note`, `created_at`)
SELECT t.`id`, 'CREATE', NULL, t.`status`, t.`creator_id`, t.`creator`, '历史工单迁移', t.`created_at`
FROM `tickets` t
WHERE NOT EXISTS (
  SELECT 1 FROM `ticket_events` e WHERE e.`ticket_id` = t.`id` AND e.`action` = 'CREATE'
);

-- 10) 迁移结果核对
SELECT 'tickets_total' AS metric, COUNT(*) AS value FROM `tickets`
UNION ALL SELECT 'backup_total', COUNT(*) FROM `tickets_phase2_backup`
UNION ALL SELECT CONCAT('status_', `status`), COUNT(*) FROM `tickets` GROUP BY `status`
UNION ALL SELECT 'sla_due_missing', COUNT(*) FROM `tickets` WHERE `sla_due_at` IS NULL
UNION ALL SELECT 'events_total', COUNT(*) FROM `ticket_events`;

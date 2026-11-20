-- Phase 6：数据分析与运营管理中心 统计索引（MySQL 8.x，可重复执行）
-- 只新增二级索引，不修改任何列与数据，无破坏性变更。
-- 执行方式：mysql -u <user> -p serviceflow < deploy/migrations/20261116-phase6-analytics-indexes.sql
USE `serviceflow`;

-- 1) tickets：人员 + 区间统计（按 assignee_id 过滤后再按 created_at 范围扫描）
SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'tickets' AND index_name = 'idx_tickets_assignee_created');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD INDEX `idx_tickets_assignee_created` (`assignee_id`, `created_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) tickets：解决/关闭趋势（status + resolved_at 范围）
SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'tickets' AND index_name = 'idx_tickets_status_resolved');
SET @ddl = IF(@has = 0, 'ALTER TABLE `tickets` ADD INDEX `idx_tickets_status_resolved` (`status`, `resolved_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) ai_analyses：AI 调用时间区间 + 状态聚合
SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ai_analyses' AND index_name = 'idx_ai_analyses_status_created');
SET @ddl = IF(@has = 0, 'ALTER TABLE `ai_analyses` ADD INDEX `idx_ai_analyses_status_created` (`status`, `created_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) ai_suggestions：建议状态 + 时间区间统计
SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'ai_suggestions' AND index_name = 'idx_ai_suggestions_status_created');
SET @ddl = IF(@has = 0, 'ALTER TABLE `ai_suggestions` ADD INDEX `idx_ai_suggestions_status_created` (`status`, `created_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 5) dispatch_logs：推荐/分配动作 + 时间区间统计
SET @has = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'dispatch_logs' AND index_name = 'idx_dispatch_logs_action_created');
SET @ddl = IF(@has = 0, 'ALTER TABLE `dispatch_logs` ADD INDEX `idx_dispatch_logs_action_created` (`action`, `created_at`)', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 6) 迁移结果核对：列出本次新增的 5 个索引
SELECT `table_name`, `index_name`
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND `index_name` IN (
    'idx_tickets_assignee_created',
    'idx_tickets_status_resolved',
    'idx_ai_analyses_status_created',
    'idx_ai_suggestions_status_created',
    'idx_dispatch_logs_action_created'
  )
GROUP BY `table_name`, `index_name`
ORDER BY `table_name`, `index_name`;

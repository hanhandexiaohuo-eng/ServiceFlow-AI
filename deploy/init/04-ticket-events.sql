-- 第三阶段：工单事件表（创建、编辑、分配、接单、开始、解决、关闭、取消、转交）。
-- 详情页时间线与操作记录的唯一数据来源，不再依赖 updated_at 猜测。
USE `serviceflow`;

CREATE TABLE IF NOT EXISTS `ticket_events` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '事件主键',
  `ticket_id`     BIGINT UNSIGNED NOT NULL COMMENT '工单 id',
  `action`        VARCHAR(24)  NOT NULL COMMENT 'CREATE/UPDATE/ASSIGN/TRANSFER/ACCEPT/START/RESOLVE/CLOSE/CANCEL',
  `from_status`   VARCHAR(16)  NULL COMMENT '变更前状态',
  `to_status`     VARCHAR(16)  NULL COMMENT '变更后状态',
  `operator_id`   BIGINT UNSIGNED NULL COMMENT '操作人用户 id',
  `operator_name` VARCHAR(64)  NOT NULL COMMENT '操作人显示名',
  `note`          VARCHAR(500) NULL COMMENT '说明或原因',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_events_ticket` (`ticket_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='工单流转事件表';

-- 第二阶段：工单表。可重复执行（IF NOT EXISTS），不删除已有数据。
-- 本机 MySQL 需先执行 01-init.sql 建库；Docker 环境会自动按文件名顺序执行本目录脚本。
USE `serviceflow`;

CREATE TABLE IF NOT EXISTS `tickets` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工单主键',
  `ticket_no`   VARCHAR(32)  NOT NULL COMMENT '工单编号，系统生成，如 SF20261114-AB12',
  `title`       VARCHAR(200) NOT NULL COMMENT '工单标题',
  `description` TEXT         NULL COMMENT '工单描述',
  `type`        VARCHAR(32)  NOT NULL COMMENT '类型：INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT',
  `priority`    VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '优先级：LOW/MEDIUM/HIGH/URGENT',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/IN_PROGRESS/RESOLVED/CLOSED',
  `creator`     VARCHAR(64)  NOT NULL COMMENT '创建人（登录体系接入前手填）',
  `assignee`    VARCHAR(64)  NULL COMMENT '处理人',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tickets_ticket_no` (`ticket_no`),
  KEY `idx_tickets_status` (`status`),
  KEY `idx_tickets_priority` (`priority`),
  KEY `idx_tickets_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='工单表';

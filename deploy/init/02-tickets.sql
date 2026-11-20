-- 第二阶段：工单表。第三阶段扩展工作流与 SLA 字段。
-- 可重复执行（IF NOT EXISTS），不删除已有数据。
-- 已有数据库升级请执行 deploy/migrations/20261116-phase3-workflow.sql。
USE `serviceflow`;

CREATE TABLE IF NOT EXISTS `tickets` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工单主键',
  `ticket_no`         VARCHAR(32)  NOT NULL COMMENT '工单编号，系统生成，如 SF20261114-AB12',
  `title`             VARCHAR(200) NOT NULL COMMENT '工单标题',
  `description`       TEXT         NULL COMMENT '工单描述',
  `type`              VARCHAR(32)  NOT NULL COMMENT '类型：INCIDENT/SERVICE_REQUEST/CHANGE/CONSULT',
  `priority`          VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '优先级：LOW/MEDIUM/HIGH/URGENT',
  `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                      COMMENT '状态：PENDING/ASSIGNED/ACCEPTED/PROCESSING/RESOLVED/CLOSED/CANCELLED',
  `creator`           VARCHAR(64)  NOT NULL COMMENT '创建人显示名',
  `creator_id`        BIGINT UNSIGNED NULL COMMENT '创建人用户 id，历史数据可能为空',
  `assignee`          VARCHAR(64)  NULL COMMENT '处理人显示名',
  `assignee_id`       BIGINT UNSIGNED NULL COMMENT '处理人用户 id，历史数据可能为空',
  `sla_due_at`        DATETIME     NULL COMMENT 'SLA 响应截止时间',
  `first_response_at` DATETIME     NULL COMMENT '首次接单（响应）时间',
  `resolved_at`       DATETIME     NULL COMMENT '解决时间',
  `closed_at`         DATETIME     NULL COMMENT '关闭时间',
  `cancelled_at`      DATETIME     NULL COMMENT '取消时间',
  `resolution`        TEXT         NULL COMMENT '解决说明',
  `cancel_reason`     VARCHAR(500) NULL COMMENT '取消原因',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tickets_ticket_no` (`ticket_no`),
  KEY `idx_tickets_status` (`status`),
  KEY `idx_tickets_priority` (`priority`),
  KEY `idx_tickets_created_at` (`created_at`),
  KEY `idx_tickets_sla_due_at` (`sla_due_at`),
  KEY `idx_tickets_assignee_id` (`assignee_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='工单表';

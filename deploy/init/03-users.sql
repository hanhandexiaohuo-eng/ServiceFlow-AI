-- 模块 1：auth+user 用户表
-- 说明：
--   password 存 BCrypt 哈希（输出最长 60 字符，VARCHAR(72) 留有余量），任何接口都不得返回。
--   role 用 varchar 存枚举名（ADMIN/AGENT/USER），与 tickets 表风格一致。
--   初始 admin 不在 SQL 中硬编码，由后端启动时按 ADMIN_INITIAL_PASSWORD 环境变量创建。
USE serviceflow;

CREATE TABLE IF NOT EXISTS users (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(32)  NOT NULL UNIQUE COMMENT '登录名',
  password    VARCHAR(72)  NOT NULL COMMENT 'BCrypt 哈希，任何接口都不得返回',
  name        VARCHAR(64)  NOT NULL COMMENT '显示名',
  role        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/AGENT/USER',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

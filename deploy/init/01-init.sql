-- 第一阶段只需要数据库本身，业务表留到第二阶段。
-- Docker 官方镜像会根据 MYSQL_DATABASE 自动建库，这里显式声明字符集，方便本地 MySQL 复用同一份脚本。
CREATE DATABASE IF NOT EXISTS `serviceflow`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

ALTER DATABASE `serviceflow`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

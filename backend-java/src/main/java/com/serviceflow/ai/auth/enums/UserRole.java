package com.serviceflow.ai.auth.enums;

/**
 * 用户角色。与 users.role 列存的字符串一致。
 * ADMIN 管理员（可删工单）；AGENT 处理人（为后续智能调度预留）；USER 普通用户。
 */
public enum UserRole {
    ADMIN, AGENT, USER
}

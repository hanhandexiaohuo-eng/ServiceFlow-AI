package com.serviceflow.ai.auth.entity;

import java.time.LocalDateTime;

/**
 * 用户实体：与 users 表一一对应。
 * password 存 BCrypt 哈希。toString 刻意排除 password，防止日志意外泄露哈希。
 */
public class User {

    private Long id;
    private String username;
    private String password;
    private String name;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 刻意不含 password：任何日志打印实体都不会泄露哈希 */
    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username + ", name=" + name
                + ", role=" + role + ", status=" + status + "}";
    }
}

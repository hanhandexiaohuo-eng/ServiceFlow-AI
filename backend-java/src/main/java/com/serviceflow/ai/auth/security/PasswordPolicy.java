package com.serviceflow.ai.auth.security;

import java.util.Locale;
import java.util.Set;

/**
 * 密码强度策略：至少 12 位，必须同时包含字母和数字，不得与用户名相同，
 * 不得使用常见弱口令。只用于新建/修改密码，不改变既有密码的校验方式。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** BCrypt 输入上限（字节） */
    public static final int MAX_BYTES = 72;

    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "123456", "12345678", "123456789", "password", "password1", "passw0rd",
            "admin123", "admin1234", "qwerty123", "abc123456", "111111111", "user1234");

    private PasswordPolicy() {
    }

    /** 校验失败抛 IllegalArgumentException（全局异常处理器转 400） */
    public static void validate(String rawPassword, String username) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (rawPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("密码长度至少 " + MIN_LENGTH + " 位");
        }
        if (rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("密码过长（最多 " + MAX_BYTES + " 字节）");
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = rawPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("密码必须同时包含字母和数字");
        }
        String normalized = rawPassword.toLowerCase(Locale.ROOT);
        if (username != null && normalized.contains(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("密码不能包含用户名");
        }
        if (WEAK_PASSWORDS.contains(normalized)) {
            throw new IllegalArgumentException("密码过于常见，请更换更强的密码");
        }
    }
}

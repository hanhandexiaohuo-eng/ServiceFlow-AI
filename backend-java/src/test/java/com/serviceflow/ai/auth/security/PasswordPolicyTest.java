package com.serviceflow.ai.auth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 密码策略单元测试：弱口令、长度、字符构成与用户名包含关系。 */
class PasswordPolicyTest {

    @Test
    void acceptsStrongPassword() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("ServiceFlow2026!", "admin"));
    }

    @Test
    void rejectsBlankPassword() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("  ", "admin"));
    }

    @Test
    void rejectsTooShortPassword() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Abc12345", "admin"));
    }

    @Test
    void rejectsPasswordWithoutDigit() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("OnlyLettersHere", "admin"));
    }

    @Test
    void rejectsPasswordWithoutLetter() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("123456789012", "admin"));
    }

    @Test
    void rejectsPasswordContainingUsername() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("AdminStrong2026", "admin"));
    }

    @Test
    void rejectsCommonWeakPassword() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("123456", "admin"));
    }

    @Test
    void rejectsOverlongPassword() {
        String overlong = "Abc12345" + "x".repeat(80);
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate(overlong, "admin"));
    }
}

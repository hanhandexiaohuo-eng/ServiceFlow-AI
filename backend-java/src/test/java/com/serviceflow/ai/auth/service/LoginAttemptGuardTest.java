package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 登录限流单元测试：窗口内累计失败达到阈值后拒绝，重置后恢复。 */
class LoginAttemptGuardTest {

    private static final String KEY = "admin|127.0.0.1";
    private static final int THRESHOLD = 10;

    @Test
    void allowsAttemptsBelowThreshold() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        for (int i = 0; i < THRESHOLD - 1; i++) {
            guard.recordFailure(KEY);
        }

        assertDoesNotThrow(() -> guard.assertAllowed(KEY));
    }

    @Test
    void blocksAfterThresholdReached() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        for (int i = 0; i < THRESHOLD; i++) {
            guard.recordFailure(KEY);
        }

        assertThrows(TooManyRequestsException.class, () -> guard.assertAllowed(KEY));
    }

    @Test
    void resetClearsFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        for (int i = 0; i < THRESHOLD; i++) {
            guard.recordFailure(KEY);
        }
        guard.reset(KEY);

        assertDoesNotThrow(() -> guard.assertAllowed(KEY));
    }

    @Test
    void differentKeysAreIndependent() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        for (int i = 0; i < THRESHOLD; i++) {
            guard.recordFailure(KEY);
        }

        assertDoesNotThrow(() -> guard.assertAllowed("bob|10.0.0.1"));
    }
}

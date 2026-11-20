package com.serviceflow.ai.intelligence.governance.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量熔断：同一供应商+模型在 2 分钟内连续失败 5 次后熔断 30 秒。
 * 熔断期间优先走备用模型；没有备用模型时直接返回可读错误，避免持续打满上游。
 */
@Component
public class AiCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long WINDOW_MS = 120_000;
    private static final long OPEN_MS = 30_000;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean isOpen(String key) {
        State state = states.get(key);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.openUntil == 0) {
                return false;
            }
            if (System.currentTimeMillis() < state.openUntil) {
                return true;
            }
            // 熔断到期后进入半开：清空窗口，放行一次探测
            state.openUntil = 0;
            state.failures = 0;
            state.windowStart = System.currentTimeMillis();
            return false;
        }
    }

    public void recordSuccess(String key) {
        states.remove(key);
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        State state = states.computeIfAbsent(key, ignored -> new State(now));
        synchronized (state) {
            if (now - state.windowStart > WINDOW_MS) {
                state.windowStart = now;
                state.failures = 0;
            }
            state.failures++;
            if (state.failures >= FAILURE_THRESHOLD) {
                state.openUntil = now + OPEN_MS;
            }
        }
    }

    private static final class State {
        private long windowStart;
        private int failures;
        private long openUntil;

        private State(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}

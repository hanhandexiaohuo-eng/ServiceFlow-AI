package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：单实例内存实现（当前部署为单副本）。
 * 同一「用户名+来源 IP」在 10 分钟内失败达到 10 次后拒绝继续尝试，
 * 返回与账号是否存在无关的统一提示，避免用户名枚举。
 */
@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    /** 未超限直接返回；超限抛 429 */
    public void assertAllowed(String key) {
        Deque<Long> queue = failures.get(key);
        if (queue == null) {
            return;
        }
        synchronized (queue) {
            long threshold = System.currentTimeMillis() - WINDOW.toMillis();
            while (!queue.isEmpty() && queue.peekFirst() < threshold) {
                queue.pollFirst();
            }
            if (queue.size() >= MAX_FAILURES) {
                throw new TooManyRequestsException("登录尝试过于频繁，请稍后再试");
            }
        }
    }

    public void recordFailure(String key) {
        cleanupIfNeeded();
        Deque<Long> queue = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            long threshold = System.currentTimeMillis() - WINDOW.toMillis();
            while (!queue.isEmpty() && queue.peekFirst() < threshold) {
                queue.pollFirst();
            }
            queue.addLast(System.currentTimeMillis());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void cleanupIfNeeded() {
        if (failures.size() < MAX_TRACKED_KEYS) {
            return;
        }
        long threshold = System.currentTimeMillis() - WINDOW.toMillis();
        failures.entrySet().removeIf(entry -> {
            Deque<Long> queue = entry.getValue();
            synchronized (queue) {
                return queue.isEmpty() || queue.peekLast() < threshold;
            }
        });
    }
}

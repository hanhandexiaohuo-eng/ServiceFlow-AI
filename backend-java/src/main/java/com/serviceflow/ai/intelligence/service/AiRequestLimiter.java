package com.serviceflow.ai.intelligence.service;

import com.serviceflow.ai.intelligence.client.AiServiceException;
import com.serviceflow.ai.intelligence.config.AiProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例进程内限流：同一用户每分钟最多 N 次 AI 请求。 */
@Component
public class AiRequestLimiter {

    private final AiProperties properties;
    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    public AiRequestLimiter(AiProperties properties) {
        this.properties = properties;
    }

    public void check(Long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> events = windows.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (events) {
            while (!events.isEmpty() && now - events.peekFirst() > 60_000) {
                events.pollFirst();
            }
            if (events.size() >= properties.getUserRateLimitPerMinute()) {
                throw new AiServiceException("AI_RATE_LIMITED", "AI 请求过于频繁，请稍后重试", 429);
            }
            events.addLast(now);
        }
    }
}

package com.serviceflow.ai.health.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.health.dto.ComponentCheck;
import com.serviceflow.ai.intelligence.config.AiProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Python AI 服务的就绪探测。
 * 使用独立的短超时客户端（1 秒），避免就绪检查被 90 秒的模型读超时拖住。
 * 只返回状态与异常类型，不暴露内部地址与密钥。
 */
@Component
public class AiHealthChecker {

    private static final String COMPONENT = "ai";

    private final AiProperties properties;
    private final RestClient restClient;

    public AiHealthChecker(AiProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(1000));
        factory.setReadTimeout(Duration.ofMillis(1000));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    public ComponentCheck check() {
        if (!properties.isEnabled()) {
            return new ComponentCheck(COMPONENT, "UP", "AI 调用已在配置中关闭");
        }
        try {
            JsonNode body = restClient.get().uri("/health").retrieve().body(JsonNode.class);
            if (body != null && "UP".equals(body.path("status").asText())) {
                return new ComponentCheck(COMPONENT, "UP", "AI 服务可用");
            }
            return new ComponentCheck(COMPONENT, "DOWN", "AI 服务返回状态异常");
        } catch (Exception e) {
            return new ComponentCheck(COMPONENT, "DOWN", "AI 服务不可用：" + e.getClass().getSimpleName());
        }
    }
}

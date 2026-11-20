package com.serviceflow.ai.intelligence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI 服务连接配置：只描述内网 HTTP 调用参数，不保存任何密钥。 */
@Component
@ConfigurationProperties(prefix = "ai.service")
public class AiProperties {

    private String baseUrl = "http://127.0.0.1:8000";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 90000;
    private boolean enabled = true;
    private int dedupeSeconds = 60;
    private int userRateLimitPerMinute = 10;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDedupeSeconds() {
        return dedupeSeconds;
    }

    public void setDedupeSeconds(int dedupeSeconds) {
        this.dedupeSeconds = dedupeSeconds;
    }

    public int getUserRateLimitPerMinute() {
        return userRateLimitPerMinute;
    }

    public void setUserRateLimitPerMinute(int userRateLimitPerMinute) {
        this.userRateLimitPerMinute = userRateLimitPerMinute;
    }
}

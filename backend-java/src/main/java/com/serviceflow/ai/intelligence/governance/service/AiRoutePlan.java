package com.serviceflow.ai.intelligence.governance.service;

/**
 * 一次 AI 调用的确定路由：供应商、模型与允许参数。
 * apiKey 只在内存与内网请求体中存在，禁止写入日志、审计或任何响应。
 */
public record AiRoutePlan(Long providerId, Long modelId, String providerCode, String baseUrl,
                          String apiKey, String model, double temperature, int maxTokens,
                          int timeoutSeconds, int maxRetries, long costPer1kMicros,
                          AiRoutePlan fallback) {

    public String circuitKey() {
        return providerId + ":" + modelId;
    }
}

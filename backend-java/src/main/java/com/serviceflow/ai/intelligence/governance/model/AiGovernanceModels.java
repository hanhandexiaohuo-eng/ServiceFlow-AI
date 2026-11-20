package com.serviceflow.ai.intelligence.governance.model;

import java.time.LocalDateTime;

/** AI 治理读模型：对外只暴露掩码与状态，密文仅限内部解析使用。 */
public final class AiGovernanceModels {

    private AiGovernanceModels() {
    }

    public record Provider(Long id, String code, String displayName, String baseUrl, String keyMask,
                           boolean hasKey, boolean enabled, LocalDateTime lastTestAt,
                           String lastTestResult, String lastTestMessage,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 仅路由解析与连接测试内部使用，禁止出现在任何响应或日志中。 */
    public record ProviderSecret(Long id, String code, String displayName, String baseUrl,
                                 String apiKeyCipher, boolean enabled) {
    }

    public record Model(Long id, Long providerId, String providerCode, String modelCode, String displayName,
                        boolean enabled, boolean verified, boolean isDefault, double temperature,
                        int maxTokens, int timeoutSeconds, long costPer1kMicros,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record ScenarioRoute(Long id, String scenario, Long primaryModelId, Long fallbackModelId,
                                Double temperature, Integer maxTokens, Integer timeoutSeconds,
                                int maxRetries, boolean enabled, LocalDateTime updatedAt) {
    }

    public record Quota(Long id, String scopeType, String scopeValue, String period,
                        int maxCalls, boolean enabled) {
    }
}

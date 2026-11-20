package com.serviceflow.ai.intelligence.governance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** AI 治理写请求：全部经过 Bean Validation，密钥字段在响应与日志中均不回显。 */
public final class AiGovernanceDtos {

    private AiGovernanceDtos() {
    }

    public record ProviderCreate(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}", message = "供应商编码只能包含字母、数字、- 和 _")
            String code,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(max = 255) @Pattern(regexp = "^https?://.+", message = "Base URL 必须以 http:// 或 https:// 开头")
            String baseUrl,
            @NotBlank @Size(max = 512) String apiKey,
            Boolean enabled) {
    }

    public record ProviderUpdate(
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(max = 255) @Pattern(regexp = "^https?://.+", message = "Base URL 必须以 http:// 或 https:// 开头")
            String baseUrl) {
    }

    public record ProviderKeyUpdate(@NotBlank @Size(max = 512) String apiKey) {
    }

    public record StatusUpdate(Boolean enabled) {
    }

    public record TestRequest(@Size(max = 64) String modelCode) {
    }

    public record ModelCreate(
            @NotNull Long providerId,
            @NotBlank @Size(max = 64) String modelCode,
            @NotBlank @Size(max = 64) String displayName,
            @Min(0) @Max(2) Double temperature,
            @Min(64) @Max(32768) Integer maxTokens,
            @Min(5) @Max(600) Integer timeoutSeconds,
            @Min(0) Long costPer1kMicros) {
    }

    public record ModelUpdate(
            @NotBlank @Size(max = 64) String displayName,
            @Min(0) @Max(2) Double temperature,
            @Min(64) @Max(32768) Integer maxTokens,
            @Min(5) @Max(600) Integer timeoutSeconds,
            @Min(0) Long costPer1kMicros) {
    }

    public record RouteUpdate(
            Long primaryModelId,
            Long fallbackModelId,
            @Min(0) @Max(2) Double temperature,
            @Min(64) @Max(32768) Integer maxTokens,
            @Min(5) @Max(600) Integer timeoutSeconds,
            @Min(0) @Max(3) Integer maxRetries,
            Boolean enabled) {
    }

    public record QuotaUpdate(
            @NotBlank @Pattern(regexp = "GLOBAL|DEPARTMENT|ROLE|USER|SCENARIO") String scopeType,
            @Size(max = 64) String scopeValue,
            @NotBlank @Pattern(regexp = "DAY|MONTH") String period,
            @NotNull @Min(1) @Max(1000000) Integer maxCalls,
            Boolean enabled) {
    }
}

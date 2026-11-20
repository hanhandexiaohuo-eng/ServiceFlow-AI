package com.serviceflow.ai.intelligence.governance.vo;

import java.time.LocalDateTime;
import java.util.List;

/** AI 治理对外响应模型：不包含密钥、密文或完整 Base URL 之外的任何凭据。 */
public final class AiGovernanceResponses {

    private AiGovernanceResponses() {
    }

    public record TestResult(boolean success, String message, long latencyMs) {
    }

    public record ScenarioStat(String scenario, long total, long success, long failed,
                               long tokens, long costMicros, long avgLatencyMs) {
    }

    public record CallStats(int days, long total, long success, long failed, double failureRate,
                            long tokens, long costMicros, long avgLatencyMs,
                            long lastHourFailures, boolean alerting, List<ScenarioStat> scenarios) {
    }

    public record CallLogView(long id, Long analysisId, String scenario, String modelName,
                              int attempt, String status, String errorCode, Integer latencyMs,
                              Integer totalTokens, Long costMicros, Long actorId,
                              String actorDepartment, LocalDateTime createdAt) {
    }
}

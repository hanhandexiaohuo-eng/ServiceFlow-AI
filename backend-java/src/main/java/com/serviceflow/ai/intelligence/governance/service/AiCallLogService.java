package com.serviceflow.ai.intelligence.governance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.intelligence.governance.repository.AiCallLogRepository;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallLogView;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallStats;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.ScenarioStat;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** AI 调用记录与统计：只保存指标，不保存 Prompt、响应内容或密钥。 */
@Service
public class AiCallLogService {

    private static final int ALERT_FAILURE_COUNT = 5;

    private final AiCallLogRepository repository;
    private final UserRepository userRepository;

    public AiCallLogService(AiCallLogRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    public void recordSuccess(Long analysisId, String scenario, AiRoutePlan plan, int attempt,
                              int latencyMs, JsonNode usage, Long actorId) {
        if (plan == null) {
            return;
        }
        Integer prompt = intOrNull(usage, "promptTokens");
        Integer completion = intOrNull(usage, "completionTokens");
        Integer total = intOrNull(usage, "totalTokens");
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        Long cost = total == null ? null : Math.round(total * plan.costPer1kMicros() / 1000.0d);
        User actor = actor(actorId);
        repository.insert(analysisId, scenario, plan.providerId(), plan.modelId(), plan.model(),
                attempt, "SUCCESS", null, latencyMs, prompt, completion, total, cost,
                actorId, actor == null ? null : actor.getDepartment(),
                actor == null ? null : actor.getRole());
    }

    public void recordFailure(Long analysisId, String scenario, AiRoutePlan plan, int attempt,
                              String errorCode, int latencyMs, Long actorId) {
        User actor = actor(actorId);
        repository.insert(analysisId, scenario,
                plan == null ? null : plan.providerId(), plan == null ? null : plan.modelId(),
                plan == null ? null : plan.model(), attempt, "FAILED", errorCode, latencyMs,
                null, null, null, null, actorId,
                actor == null ? null : actor.getDepartment(),
                actor == null ? null : actor.getRole());
    }

    private User actor(Long actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            return userRepository.findById(actorId).orElse(null);
        } catch (DataAccessException e) {
            return null;
        }
    }

    public CallStats stats(int days) {
        int safeDays = Math.max(1, Math.min(90, days));
        LocalDateTime since = LocalDate.now().minusDays(safeDays - 1L).atStartOfDay();
        List<ScenarioStat> scenarios = repository.stats(since);
        long total = scenarios.stream().mapToLong(ScenarioStat::total).sum();
        long success = scenarios.stream().mapToLong(ScenarioStat::success).sum();
        long failed = scenarios.stream().mapToLong(ScenarioStat::failed).sum();
        long tokens = scenarios.stream().mapToLong(ScenarioStat::tokens).sum();
        long cost = scenarios.stream().mapToLong(ScenarioStat::costMicros).sum();
        long latencySum = scenarios.stream().mapToLong(item -> item.avgLatencyMs() * item.total()).sum();
        long avgLatency = total == 0 ? 0 : latencySum / total;
        double failureRate = total == 0 ? 0 : Math.round(failed * 10000.0d / total) / 100.0d;
        long lastHourFailures = repository.countFailuresSince(LocalDateTime.now().minusHours(1));
        return new CallStats(safeDays, total, success, failed, failureRate, tokens, cost, avgLatency,
                lastHourFailures, lastHourFailures >= ALERT_FAILURE_COUNT, scenarios);
    }

    public List<CallLogView> recent(int limit) {
        return repository.recent(Math.max(1, Math.min(200, limit)));
    }

    private Integer intOrNull(JsonNode usage, String field) {
        if (usage == null || !usage.hasNonNull(field)) {
            return null;
        }
        return usage.get(field).asInt();
    }
}

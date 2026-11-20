package com.serviceflow.ai.intelligence.governance.repository;

import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallLogView;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.ScenarioStat;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** AI 调用记录：只追加指标，不保存 Prompt、响应正文或任何密钥。 */
@Repository
public class AiCallLogRepository {

    private static final RowMapper<CallLogView> CALL_MAPPER = (rs, rowNum) -> new CallLogView(
            rs.getLong("id"),
            rs.getObject("analysis_id") == null ? null : rs.getLong("analysis_id"),
            rs.getString("scenario"),
            rs.getString("model_name"),
            rs.getInt("attempt"),
            rs.getString("status"),
            rs.getString("error_code"),
            rs.getObject("latency_ms") == null ? null : rs.getInt("latency_ms"),
            rs.getObject("total_tokens") == null ? null : rs.getInt("total_tokens"),
            rs.getObject("cost_micros") == null ? null : rs.getLong("cost_micros"),
            rs.getObject("actor_id") == null ? null : rs.getLong("actor_id"),
            rs.getString("actor_department"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public AiCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long analysisId, String scenario, Long providerId, Long modelId, String modelName,
                       int attempt, String status, String errorCode, Integer latencyMs,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens,
                       Long costMicros, Long actorId, String actorDepartment, String actorRole) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO ai_call_logs
                                (analysis_id, scenario, provider_id, model_id, model_name, attempt, status,
                                 error_code, latency_ms, prompt_tokens, completion_tokens, total_tokens,
                                 cost_micros, actor_id, actor_department, actor_role, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, analysisId, scenario, providerId, modelId, modelName, attempt, status,
                    errorCode, latencyMs, promptTokens, completionTokens, totalTokens,
                    costMicros, actorId, actorDepartment, actorRole, LocalDateTime.now());
        } catch (DataAccessException e) {
            // 迁移未执行时不影响 AI 主流程，但不能静默：服务端日志只记录异常类型
            org.slf4j.LoggerFactory.getLogger(AiCallLogRepository.class)
                    .warn("ai_call_log_write_failed type={}", e.getClass().getSimpleName());
        }
    }

    public long countSince(String scopeType, Long userId, String department, String role, String scenario,
                           LocalDateTime since) {
        try {
            return switch (scopeType) {
                case "USER" -> query("SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ? AND actor_id = ?",
                        since, userId);
                case "DEPARTMENT" -> query(
                        "SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ? AND actor_department = ?",
                        since, department);
                case "ROLE" -> query(
                        "SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ? AND actor_role = ?",
                        since, role);
                case "SCENARIO" -> query(
                        "SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ? AND scenario = ?",
                        since, scenario);
                default -> query("SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ?", since);
            };
        } catch (DataAccessException e) {
            return 0;
        }
    }

    public List<ScenarioStat> stats(LocalDateTime since) {
        try {
            return jdbcTemplate.query("""
                    SELECT scenario,
                           COUNT(*) AS total,
                           COALESCE(SUM(status = 'SUCCESS'), 0) AS success,
                           COALESCE(SUM(status = 'FAILED'), 0) AS failed,
                           COALESCE(SUM(total_tokens), 0) AS tokens,
                           COALESCE(SUM(cost_micros), 0) AS cost,
                           COALESCE(ROUND(AVG(latency_ms)), 0) AS avg_latency
                    FROM ai_call_logs
                    WHERE created_at >= ?
                    GROUP BY scenario
                    ORDER BY total DESC
                    """, (rs, rowNum) -> new ScenarioStat(
                    rs.getString("scenario"),
                    rs.getLong("total"),
                    rs.getLong("success"),
                    rs.getLong("failed"),
                    rs.getLong("tokens"),
                    rs.getLong("cost"),
                    rs.getLong("avg_latency")), since);
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    public long countFailuresSince(LocalDateTime since) {
        try {
            Long value = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ? AND status = 'FAILED'",
                    Long.class, since);
            return value == null ? 0 : value;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    public long countSinceTime(LocalDateTime since) {
        try {
            Long value = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_call_logs WHERE created_at >= ?", Long.class, since);
            return value == null ? 0 : value;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    public List<CallLogView> recent(int limit) {
        try {
            return jdbcTemplate.query(
                    "SELECT * FROM ai_call_logs ORDER BY id DESC LIMIT ?", CALL_MAPPER, limit);
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    private long query(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}

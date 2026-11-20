package com.serviceflow.ai.intelligence.governance.repository;

import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Model;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Provider;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ProviderSecret;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Quota;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ScenarioRoute;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** AI 治理配置数据访问：不含任何业务指标统计，调用记录见 AiCallLogRepository。 */
@Repository
public class AiGovernanceRepository {

    private static final RowMapper<Provider> PROVIDER_MAPPER = (rs, rowNum) -> new Provider(
            rs.getLong("id"),
            rs.getString("provider_code"),
            rs.getString("display_name"),
            rs.getString("base_url"),
            rs.getString("api_key_mask"),
            rs.getString("api_key_cipher") != null,
            rs.getInt("enabled") == 1,
            toLocal(rs.getTimestamp("last_test_at")),
            rs.getString("last_test_result"),
            rs.getString("last_test_message"),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static final RowMapper<ProviderSecret> SECRET_MAPPER = (rs, rowNum) -> new ProviderSecret(
            rs.getLong("id"),
            rs.getString("provider_code"),
            rs.getString("display_name"),
            rs.getString("base_url"),
            rs.getString("api_key_cipher"),
            rs.getInt("enabled") == 1);

    private static final RowMapper<Model> MODEL_MAPPER = (rs, rowNum) -> new Model(
            rs.getLong("id"),
            rs.getLong("provider_id"),
            rs.getString("provider_code"),
            rs.getString("model_code"),
            rs.getString("display_name"),
            rs.getInt("enabled") == 1,
            rs.getInt("verified") == 1,
            rs.getInt("is_default") == 1,
            rs.getBigDecimal("temperature").doubleValue(),
            rs.getInt("max_tokens"),
            rs.getInt("timeout_seconds"),
            rs.getLong("cost_per_1k_micros"),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static final RowMapper<ScenarioRoute> ROUTE_MAPPER = (rs, rowNum) -> new ScenarioRoute(
            rs.getLong("id"),
            rs.getString("scenario"),
            nullableLong(rs.getObject("primary_model_id")),
            nullableLong(rs.getObject("fallback_model_id")),
            rs.getBigDecimal("temperature") == null ? null : rs.getBigDecimal("temperature").doubleValue(),
            rs.getObject("max_tokens") == null ? null : rs.getInt("max_tokens"),
            rs.getObject("timeout_seconds") == null ? null : rs.getInt("timeout_seconds"),
            rs.getInt("max_retries"),
            rs.getInt("enabled") == 1,
            toLocal(rs.getTimestamp("updated_at")));

    private static final RowMapper<Quota> QUOTA_MAPPER = (rs, rowNum) -> new Quota(
            rs.getLong("id"),
            rs.getString("scope_type"),
            rs.getString("scope_value"),
            rs.getString("period"),
            rs.getInt("max_calls"),
            rs.getInt("enabled") == 1);

    private final JdbcTemplate jdbcTemplate;

    public AiGovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ---------------- 供应商 ---------------- */

    public List<Provider> findProviders() {
        return jdbcTemplate.query("SELECT * FROM ai_providers ORDER BY id", PROVIDER_MAPPER);
    }

    public Optional<Provider> findProvider(Long id) {
        return jdbcTemplate.query("SELECT * FROM ai_providers WHERE id = ?", PROVIDER_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ProviderSecret> findProviderSecret(Long id) {
        return jdbcTemplate.query("SELECT * FROM ai_providers WHERE id = ?", SECRET_MAPPER, id)
                .stream().findFirst();
    }

    public boolean existsProviderCode(String code, Long excludeId) {
        Long count = excludeId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_providers WHERE provider_code = ?", Long.class, code)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_providers WHERE provider_code = ? AND id <> ?",
                        Long.class, code, excludeId);
        return count != null && count > 0;
    }

    public Long insertProvider(String code, String displayName, String baseUrl, String cipher, String mask,
                               boolean enabled, Long actorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ai_providers
                        (provider_code, display_name, base_url, api_key_cipher, api_key_mask,
                         api_key_updated_at, enabled, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, displayName);
            ps.setString(3, baseUrl);
            ps.setString(4, cipher);
            ps.setString(5, mask);
            ps.setObject(6, cipher == null ? null : LocalDateTime.now());
            ps.setInt(7, enabled ? 1 : 0);
            ps.setObject(8, actorId);
            ps.setObject(9, actorId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public void updateProvider(Long id, String displayName, String baseUrl, Long actorId) {
        jdbcTemplate.update("UPDATE ai_providers SET display_name = ?, base_url = ?, updated_by = ? WHERE id = ?",
                displayName, baseUrl, actorId, id);
    }

    public void updateProviderKey(Long id, String cipher, String mask, Long actorId) {
        jdbcTemplate.update("""
                        UPDATE ai_providers
                        SET api_key_cipher = ?, api_key_mask = ?, api_key_updated_at = ?, updated_by = ?
                        WHERE id = ?
                        """, cipher, mask, LocalDateTime.now(), actorId, id);
    }

    public void updateProviderStatus(Long id, boolean enabled, Long actorId) {
        jdbcTemplate.update("UPDATE ai_providers SET enabled = ?, updated_by = ? WHERE id = ?",
                enabled ? 1 : 0, actorId, id);
    }

    public void updateProviderTest(Long id, boolean success, String message) {
        jdbcTemplate.update("""
                        UPDATE ai_providers
                        SET last_test_at = ?, last_test_result = ?, last_test_message = ?
                        WHERE id = ?
                        """, LocalDateTime.now(), success ? "SUCCESS" : "FAILURE", message, id);
    }

    /* ---------------- 模型 ---------------- */

    public List<Model> findModels() {
        return jdbcTemplate.query("""
                SELECT m.*, p.provider_code AS provider_code
                FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
                ORDER BY p.id, m.id
                """, MODEL_MAPPER);
    }

    public Optional<Model> findModel(Long id) {
        return jdbcTemplate.query("""
                SELECT m.*, p.provider_code AS provider_code
                FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
                WHERE m.id = ?
                """, MODEL_MAPPER, id).stream().findFirst();
    }

    public Optional<Model> findDefaultModel() {
        return jdbcTemplate.query("""
                SELECT m.*, p.provider_code AS provider_code
                FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
                WHERE m.is_default = 1 AND m.enabled = 1 AND m.verified = 1 AND p.enabled = 1
                ORDER BY m.id LIMIT 1
                """, MODEL_MAPPER).stream().findFirst();
    }

    public boolean existsModelCode(Long providerId, String modelCode, Long excludeId) {
        Long count = excludeId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_models WHERE provider_id = ? AND model_code = ?",
                        Long.class, providerId, modelCode)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_models WHERE provider_id = ? AND model_code = ? AND id <> ?",
                        Long.class, providerId, modelCode, excludeId);
        return count != null && count > 0;
    }

    public Long insertModel(Long providerId, String modelCode, String displayName, boolean enabled,
                            double temperature, int maxTokens, int timeoutSeconds, long costPer1kMicros,
                            Long actorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ai_models
                        (provider_id, model_code, display_name, enabled, verified, is_default,
                         temperature, max_tokens, timeout_seconds, cost_per_1k_micros, created_by, updated_by)
                    VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, providerId);
            ps.setString(2, modelCode);
            ps.setString(3, displayName);
            ps.setInt(4, enabled ? 1 : 0);
            ps.setBigDecimal(5, java.math.BigDecimal.valueOf(temperature));
            ps.setInt(6, maxTokens);
            ps.setInt(7, timeoutSeconds);
            ps.setLong(8, costPer1kMicros);
            ps.setObject(9, actorId);
            ps.setObject(10, actorId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public void updateModel(Long id, String displayName, double temperature, int maxTokens,
                            int timeoutSeconds, long costPer1kMicros, Long actorId) {
        jdbcTemplate.update("""
                        UPDATE ai_models
                        SET display_name = ?, temperature = ?, max_tokens = ?, timeout_seconds = ?,
                            cost_per_1k_micros = ?, updated_by = ?
                        WHERE id = ?
                        """, displayName, java.math.BigDecimal.valueOf(temperature), maxTokens,
                timeoutSeconds, costPer1kMicros, actorId, id);
    }

    public void updateModelStatus(Long id, boolean enabled, Long actorId) {
        jdbcTemplate.update("UPDATE ai_models SET enabled = ?, updated_by = ? WHERE id = ?",
                enabled ? 1 : 0, actorId, id);
    }

    public void markModelVerified(Long id, boolean verified, Long actorId) {
        jdbcTemplate.update("UPDATE ai_models SET verified = ?, updated_by = ? WHERE id = ?",
                verified ? 1 : 0, actorId, id);
    }

    public void clearDefaultModels(Long actorId) {
        jdbcTemplate.update("UPDATE ai_models SET is_default = 0, updated_by = ? WHERE is_default = 1", actorId);
    }

    public void markDefaultModel(Long id, Long actorId) {
        jdbcTemplate.update("UPDATE ai_models SET is_default = 1, updated_by = ? WHERE id = ?", actorId, id);
    }

    /* ---------------- 场景路由 ---------------- */

    public List<ScenarioRoute> findRoutes() {
        return jdbcTemplate.query("SELECT * FROM ai_scenario_routes ORDER BY id", ROUTE_MAPPER);
    }

    public Optional<ScenarioRoute> findRoute(String scenario) {
        return jdbcTemplate.query("SELECT * FROM ai_scenario_routes WHERE scenario = ?", ROUTE_MAPPER, scenario)
                .stream().findFirst();
    }

    public void upsertRoute(String scenario, Long primaryModelId, Long fallbackModelId,
                            Double temperature, Integer maxTokens, Integer timeoutSeconds,
                            int maxRetries, boolean enabled, Long actorId) {
        jdbcTemplate.update("""
                        INSERT INTO ai_scenario_routes
                            (scenario, primary_model_id, fallback_model_id, temperature, max_tokens,
                             timeout_seconds, max_retries, enabled, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            primary_model_id = VALUES(primary_model_id),
                            fallback_model_id = VALUES(fallback_model_id),
                            temperature = VALUES(temperature),
                            max_tokens = VALUES(max_tokens),
                            timeout_seconds = VALUES(timeout_seconds),
                            max_retries = VALUES(max_retries),
                            enabled = VALUES(enabled),
                            updated_by = VALUES(updated_by)
                        """, scenario, primaryModelId, fallbackModelId,
                temperature == null ? null : java.math.BigDecimal.valueOf(temperature),
                maxTokens, timeoutSeconds, maxRetries, enabled ? 1 : 0, actorId);
    }

    /* ---------------- 配额 ---------------- */

    public List<Quota> findQuotas() {
        return jdbcTemplate.query("SELECT * FROM ai_quotas ORDER BY scope_type, scope_value", QUOTA_MAPPER);
    }

    public Optional<Quota> findQuota(Long id) {
        return jdbcTemplate.query("SELECT * FROM ai_quotas WHERE id = ?", QUOTA_MAPPER, id)
                .stream().findFirst();
    }

    public void upsertQuota(String scopeType, String scopeValue, String period, int maxCalls,
                            boolean enabled, Long actorId) {
        jdbcTemplate.update("""
                        INSERT INTO ai_quotas (scope_type, scope_value, period, max_calls, enabled, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            max_calls = VALUES(max_calls),
                            enabled = VALUES(enabled),
                            updated_by = VALUES(updated_by)
                        """, scopeType, scopeValue, period, maxCalls, enabled ? 1 : 0, actorId);
    }

    public void deleteQuota(Long id) {
        jdbcTemplate.update("DELETE FROM ai_quotas WHERE id = ?", id);
    }

    private static LocalDateTime toLocal(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}

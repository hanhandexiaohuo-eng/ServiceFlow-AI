package com.serviceflow.ai.health.service;

import com.serviceflow.ai.health.dto.ComponentCheck;
import com.serviceflow.ai.health.dto.HealthResponse;
import com.serviceflow.ai.health.dto.ReadinessResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 健康与就绪检查。
 * - liveness（/api/health）：只证明进程存活，不依赖外部组件。
 * - readiness（/api/health/ready）：MySQL 必须可用；AI 服务不可用视为 DEGRADED（核心业务可用，
 *   仅 AI 功能受影响），因此返回 200 但如实列出 DOWN 组件。
 */
@Service
public class HealthService {

    public static final String SERVICE_NAME = "serviceflow-ai-backend-java";
    public static final String VERSION = "0.1.0";

    private final JdbcTemplate jdbcTemplate;
    private final AiHealthChecker aiHealthChecker;

    public HealthService(JdbcTemplate jdbcTemplate, AiHealthChecker aiHealthChecker) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiHealthChecker = aiHealthChecker;
    }

    public HealthResponse health() {
        return new HealthResponse(SERVICE_NAME, "UP", VERSION, Instant.now());
    }

    public ReadinessResponse readiness() {
        ComponentCheck mysql = checkMysql();
        ComponentCheck ai = aiHealthChecker.check();
        String status;
        if (!"UP".equals(mysql.status())) {
            status = "DOWN";
        } else if (!"UP".equals(ai.status())) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }
        return new ReadinessResponse(SERVICE_NAME, status, VERSION, Instant.now(), List.of(mysql, ai));
    }

    private ComponentCheck checkMysql() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ComponentCheck("mysql", "UP", "数据库连接正常");
        } catch (DataAccessException e) {
            // 只暴露根因信息，Hikari/MySQL 驱动的 message 不含密码
            return new ComponentCheck("mysql", "DOWN", "数据库不可用：" + rootCauseMessage(e));
        }
    }

    private String rootCauseMessage(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause.getMessage() != null ? cause.getMessage() : e.getMessage();
    }
}

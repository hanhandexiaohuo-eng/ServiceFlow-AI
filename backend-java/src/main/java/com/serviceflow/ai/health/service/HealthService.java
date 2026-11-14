package com.serviceflow.ai.health.service;

import com.serviceflow.ai.health.dto.ComponentCheck;
import com.serviceflow.ai.health.dto.HealthResponse;
import com.serviceflow.ai.health.dto.ReadinessResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class HealthService {

    public static final String SERVICE_NAME = "serviceflow-ai-backend-java";
    public static final String VERSION = "0.1.0";

    private final JdbcTemplate jdbcTemplate;

    public HealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HealthResponse health() {
        return new HealthResponse(SERVICE_NAME, "UP", VERSION, Instant.now());
    }

    public ReadinessResponse readiness() {
        ComponentCheck mysql = checkMysql();
        String status = "UP".equals(mysql.status()) ? "UP" : "DOWN";
        return new ReadinessResponse(SERVICE_NAME, status, VERSION, Instant.now(), List.of(mysql));
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

package com.serviceflow.ai.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 安全审计日志：只追加，不提供修改/删除入口。
 * detail/failureReason 必须是已脱敏摘要，禁止写入密码、Token、API Key 或完整敏感内容。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final int MAX_TEXT = 500;

    private final JdbcTemplate jdbcTemplate;

    public AuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void success(Long actorId, String actorUsername, String action,
                        String objectType, String objectId, String detail,
                        String ip, String requestId) {
        write(actorId, actorUsername, action, objectType, objectId, detail, ip, requestId, "SUCCESS", null);
    }

    public void failure(Long actorId, String actorUsername, String action,
                        String objectType, String objectId, String detail,
                        String ip, String requestId, String reason) {
        write(actorId, actorUsername, action, objectType, objectId, detail, ip, requestId, "FAILURE", reason);
    }

    private void write(Long actorId, String actorUsername, String action,
                       String objectType, String objectId, String detail,
                       String ip, String requestId, String result, String reason) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (actor_id, actor_username, action, object_type, object_id, "
                            + "detail, ip, request_id, result, failure_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    actorId, truncate(actorUsername, 64), truncate(action, 64), truncate(objectType, 64),
                    truncate(objectId, 64), truncate(detail, MAX_TEXT), truncate(ip, 64),
                    truncate(requestId, 64), truncate(result, 16), truncate(reason, 255));
        } catch (DataAccessException e) {
            // 审计失败不阻断主流程，但必须在服务端日志留痕（不含敏感内容）
            log.error("审计日志写入失败 action={} result={}", action, result);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

package com.serviceflow.ai.common.audit;

import java.time.LocalDateTime;

/** 审计日志只读视图：字段全部来自 audit_logs，不含任何凭据。 */
public record AuditLogResponse(Long id, Long actorId, String actorUsername, String action,
                               String objectType, String objectId, String detail, String ip,
                               String requestId, String result, String failureReason,
                               LocalDateTime createdAt) {
}

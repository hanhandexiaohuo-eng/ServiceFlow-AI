package com.serviceflow.ai.common.audit;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志查询：只读，仅 ADMIN。
 * 不提供修改/删除，导出能力后续单独做权限与脱敏评审。
 */
@Service
public class AuditLogQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final RowMapper<AuditLogResponse> ROW_MAPPER = (rs, rowNum) -> new AuditLogResponse(
            rs.getLong("id"),
            rs.getObject("actor_id") == null ? null : rs.getLong("actor_id"),
            rs.getString("actor_username"),
            rs.getString("action"),
            rs.getString("object_type"),
            rs.getString("object_id"),
            rs.getString("detail"),
            rs.getString("ip"),
            rs.getString("request_id"),
            rs.getString("result"),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public AuditLogQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AuditLogResponse> page(String action, String actor, String result,
                                               LocalDate from, LocalDate to, int page, int size) {
        requireAdmin();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        List<Object> args = new ArrayList<>();
        String where = buildWhere(action, actor, result, from, to, args);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE 1 = 1" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<AuditLogResponse> list = jdbcTemplate.query(
                "SELECT * FROM audit_logs WHERE 1 = 1" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        return new PageResponse<>(list, total == null ? 0 : total, safePage, safeSize);
    }

    private String buildWhere(String action, String actor, String result,
                              LocalDate from, LocalDate to, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (action != null && !action.isBlank()) {
            where.append(" AND action = ?");
            args.add(action.trim().toUpperCase());
        }
        if (actor != null && !actor.isBlank()) {
            where.append(" AND actor_username LIKE ?");
            args.add("%" + actor.trim() + "%");
        }
        if (result != null && !result.isBlank()) {
            where.append(" AND result = ?");
            args.add(result.trim().toUpperCase());
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            // 结束日期按「次日零点」做开区间，避免漏掉当天记录
            where.append(" AND created_at < ?");
            args.add(LocalDateTime.of(to.plusDays(1), java.time.LocalTime.MIDNIGHT));
        }
        return where.toString();
    }

    private void requireAdmin() {
        LoginUser user = UserContext.getRequired();
        if (user.role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可查看操作日志");
        }
    }
}

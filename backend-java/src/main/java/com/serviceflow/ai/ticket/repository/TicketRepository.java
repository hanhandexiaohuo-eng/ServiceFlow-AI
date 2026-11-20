package com.serviceflow.ai.ticket.repository;

import com.serviceflow.ai.ticket.entity.Ticket;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工单数据访问层：JdbcTemplate + 预编译占位符。
 * 状态流转必须使用 updateWorkflowState 的条件更新（WHERE status = 期望旧值），
 * 由影响行数判断是否发生并发冲突。
 */
@Repository
public class TicketRepository {

    private static final RowMapper<Ticket> ROW_MAPPER = new BeanPropertyRowMapper<>(Ticket.class);

    private final JdbcTemplate jdbcTemplate;

    public TicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入并回填自增主键 */
    public Ticket insert(Ticket t) {
        String sql = "INSERT INTO tickets (ticket_no, title, description, type, priority, status,"
                + " creator, creator_id, assignee, assignee_id, sla_due_at, first_response_at,"
                + " resolved_at, closed_at, cancelled_at, resolution, cancel_reason, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Timestamp created = Timestamp.valueOf(t.getCreatedAt());
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, t.getTicketNo());
            ps.setString(2, t.getTitle());
            ps.setString(3, t.getDescription());
            ps.setString(4, t.getType());
            ps.setString(5, t.getPriority());
            ps.setString(6, t.getStatus());
            ps.setString(7, t.getCreator());
            setNullableLong(ps, 8, t.getCreatorId());
            ps.setString(9, t.getAssignee());
            setNullableLong(ps, 10, t.getAssigneeId());
            setNullableTimestamp(ps, 11, t.getSlaDueAt());
            setNullableTimestamp(ps, 12, t.getFirstResponseAt());
            setNullableTimestamp(ps, 13, t.getResolvedAt());
            setNullableTimestamp(ps, 14, t.getClosedAt());
            setNullableTimestamp(ps, 15, t.getCancelledAt());
            ps.setString(16, t.getResolution());
            ps.setString(17, t.getCancelReason());
            ps.setTimestamp(18, created);
            ps.setTimestamp(19, created);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            t.setId(key.longValue());
        }
        return t;
    }

    public Optional<Ticket> findById(Long id) {
        List<Ticket> list = jdbcTemplate.query(
                "SELECT * FROM tickets WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public boolean existsByTicketNo(String ticketNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE ticket_no = ?", Integer.class, ticketNo);
        return count != null && count > 0;
    }

    /** 分页 + 可选筛选，status/priority 为 null 时不参与过滤。 */
    public List<Ticket> findPage(String status, String priority, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tickets WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, status, priority);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public long count(String status, String priority) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM tickets WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, status, priority);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return total != null ? total : 0;
    }

    private void appendFilter(StringBuilder sql, List<Object> params, String status, String priority) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND priority = ?");
            params.add(priority);
        }
    }

    /** 只更新可编辑内容与 SLA 截止时间；status/assignee 不在此方法内。 */
    public int updateContent(Ticket t) {
        return jdbcTemplate.update(
                "UPDATE tickets SET title = ?, description = ?, type = ?, priority = ?, sla_due_at = ? WHERE id = ?",
                t.getTitle(), t.getDescription(), t.getType(), t.getPriority(),
                toTimestamp(t.getSlaDueAt()), t.getId());
    }

    /**
     * 条件更新工作流字段。WHERE status = expectedStatus 用于并发保护：
     * 返回 0 表示工单已被其他人流转，调用方应返回 409。
     */
    public int updateWorkflowState(Ticket t, String expectedStatus) {
        return jdbcTemplate.update(
                "UPDATE tickets SET status = ?, assignee = ?, assignee_id = ?, first_response_at = ?,"
                        + " resolved_at = ?, closed_at = ?, cancelled_at = ?, resolution = ?, cancel_reason = ?"
                        + " WHERE id = ? AND status = ?",
                t.getStatus(), t.getAssignee(), t.getAssigneeId(),
                toTimestamp(t.getFirstResponseAt()), toTimestamp(t.getResolvedAt()),
                toTimestamp(t.getClosedAt()), toTimestamp(t.getCancelledAt()),
                t.getResolution(), t.getCancelReason(),
                t.getId(), expectedStatus);
    }

    /** 返回影响行数，0 表示记录不存在。 */
    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM tickets WHERE id = ?", id);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, java.time.LocalDateTime value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private static Timestamp toTimestamp(java.time.LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}

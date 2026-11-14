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
 * 工单数据访问层：只用 JdbcTemplate + 预编译占位符（?），
 * 参数由框架绑定，天然防 SQL 注入。不在这里写业务判断。
 */
@Repository
public class TicketRepository {

    private static final RowMapper<Ticket> ROW_MAPPER = new BeanPropertyRowMapper<>(Ticket.class);

    private final JdbcTemplate jdbcTemplate;

    public TicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入并回填自增主键（KeyHolder 拿到数据库生成的 id） */
    public Ticket insert(Ticket t) {
        String sql = "INSERT INTO tickets (ticket_no, title, description, type, priority, status, creator, assignee, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Timestamp now = Timestamp.valueOf(t.getCreatedAt());
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, t.getTicketNo());
            ps.setString(2, t.getTitle());
            ps.setString(3, t.getDescription());
            ps.setString(4, t.getType());
            ps.setString(5, t.getPriority());
            ps.setString(6, t.getStatus());
            ps.setString(7, t.getCreator());
            ps.setString(8, t.getAssignee());
            ps.setTimestamp(9, now);
            ps.setTimestamp(10, now);
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

    /**
     * 分页 + 可选筛选。status/priority 为 null 时不参与过滤，
     * 动态拼 WHERE 只拼固定片段，值仍然走占位符。
     */
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

    /** 全量更新（PUT 语义）。返回影响行数，0 表示记录不存在。 */
    public int update(Ticket t) {
        return jdbcTemplate.update(
                "UPDATE tickets SET title = ?, description = ?, type = ?, priority = ?, status = ?, assignee = ? WHERE id = ?",
                t.getTitle(), t.getDescription(), t.getType(),
                t.getPriority(), t.getStatus(), t.getAssignee(), t.getId());
    }

    /** 返回影响行数，0 表示记录不存在。 */
    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM tickets WHERE id = ?", id);
    }
}

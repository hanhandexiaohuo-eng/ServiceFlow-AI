package com.serviceflow.ai.ticket.repository;

import com.serviceflow.ai.ticket.entity.TicketEvent;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/** 工单事件数据访问层。 */
@Repository
public class TicketEventRepository {

    private static final RowMapper<TicketEvent> ROW_MAPPER = new BeanPropertyRowMapper<>(TicketEvent.class);

    private final JdbcTemplate jdbcTemplate;

    public TicketEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(TicketEvent e) {
        jdbcTemplate.update(
                "INSERT INTO ticket_events (ticket_id, action, from_status, to_status, operator_id,"
                        + " operator_name, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                e.getTicketId(), e.getAction(), e.getFromStatus(), e.getToStatus(),
                e.getOperatorId(), e.getOperatorName(), e.getNote(),
                Timestamp.valueOf(e.getCreatedAt()));
    }

    /** 按发生顺序正序返回，详情页直接渲染 */
    public List<TicketEvent> findByTicketId(Long ticketId) {
        return jdbcTemplate.query(
                "SELECT * FROM ticket_events WHERE ticket_id = ? ORDER BY id ASC",
                ROW_MAPPER, ticketId);
    }

    public int deleteByTicketId(Long ticketId) {
        return jdbcTemplate.update("DELETE FROM ticket_events WHERE ticket_id = ?", ticketId);
    }
}

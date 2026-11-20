package com.serviceflow.ai.dispatch.repository;

import com.serviceflow.ai.dispatch.entity.DispatchLog;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** 调度日志数据访问。 */
@Repository
public class DispatchLogRepository {

    private static final RowMapper<DispatchLog> ROW_MAPPER = new BeanPropertyRowMapper<>(DispatchLog.class);

    private final JdbcTemplate jdbcTemplate;

    public DispatchLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DispatchLog insert(DispatchLog log) {
        String sql = "INSERT INTO dispatch_logs (ticket_id, ticket_no, action, operator_id, operator_name, "
                + "agent_id, agent_name, score, candidate_count, note, detail) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, log.getTicketId());
            ps.setString(2, log.getTicketNo());
            ps.setString(3, log.getAction());
            if (log.getOperatorId() == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, log.getOperatorId());
            }
            ps.setString(5, log.getOperatorName());
            if (log.getAgentId() == null) {
                ps.setNull(6, Types.BIGINT);
            } else {
                ps.setLong(6, log.getAgentId());
            }
            ps.setString(7, log.getAgentName());
            if (log.getScore() == null) {
                ps.setNull(8, Types.DECIMAL);
            } else {
                ps.setDouble(8, log.getScore());
            }
            if (log.getCandidateCount() == null) {
                ps.setNull(9, Types.INTEGER);
            } else {
                ps.setInt(9, log.getCandidateCount());
            }
            ps.setString(10, log.getNote());
            ps.setString(11, log.getDetail());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            log.setId(key.longValue());
        }
        return log;
    }

    public List<DispatchLog> findPage(Long ticketId, Long agentId, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(ticketId, agentId, args);
        int offset = Math.max(0, (page - 1) * size);
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM dispatch_logs" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, args.toArray());
    }

    public long count(Long ticketId, Long agentId) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(ticketId, agentId, args);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_logs" + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String buildWhere(Long ticketId, Long agentId, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (ticketId != null) {
            where.append(" AND ticket_id = ?");
            args.add(ticketId);
        }
        if (agentId != null) {
            where.append(" AND agent_id = ?");
            args.add(agentId);
        }
        return where.toString();
    }
}

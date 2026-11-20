package com.serviceflow.ai.dispatch.repository;

import com.serviceflow.ai.dispatch.entity.TicketTypeSkillRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 工单类型—技能规则数据访问。 */
@Repository
public class TicketTypeSkillRuleRepository {

    /**
     * MySQL 驱动会把 TINYINT 列按布尔返回，这里显式按 int 读取，
     * 避免 BeanPropertyRowMapper 在 Boolean → Integer 上转换失败。
     */
    private static final RowMapper<TicketTypeSkillRule> ROW_MAPPER = (rs, rowNum) -> {
        TicketTypeSkillRule rule = new TicketTypeSkillRule();
        rule.setId(rs.getLong("id"));
        rule.setTicketType(rs.getString("ticket_type"));
        rule.setSkillId(rs.getLong("skill_id"));
        rule.setRequired(rs.getInt("required"));
        return rule;
    };

    private final JdbcTemplate jdbcTemplate;

    public TicketTypeSkillRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TicketTypeSkillRule> findByTicketType(String ticketType) {
        return jdbcTemplate.query(
                "SELECT * FROM ticket_type_skill_rules WHERE ticket_type = ? ORDER BY required DESC, id ASC",
                ROW_MAPPER, ticketType);
    }

    public List<TicketTypeSkillRule> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM ticket_type_skill_rules ORDER BY ticket_type ASC, required DESC, id ASC",
                ROW_MAPPER);
    }
}

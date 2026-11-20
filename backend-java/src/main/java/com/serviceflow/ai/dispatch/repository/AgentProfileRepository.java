package com.serviceflow.ai.dispatch.repository;

import com.serviceflow.ai.dispatch.entity.AgentProfile;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 人员档案数据访问。user_id 同时是人员 id。 */
@Repository
public class AgentProfileRepository {

    private static final RowMapper<AgentProfile> ROW_MAPPER = new BeanPropertyRowMapper<>(AgentProfile.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AgentProfile> findByUserId(Long userId) {
        List<AgentProfile> list = jdbcTemplate.query(
                "SELECT * FROM agent_profiles WHERE user_id = ?", ROW_MAPPER, userId);
        return list.stream().findFirst();
    }

    public List<AgentProfile> findAll() {
        return jdbcTemplate.query("SELECT * FROM agent_profiles ORDER BY user_id ASC", ROW_MAPPER);
    }

    public boolean exists(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_profiles WHERE user_id = ?", Long.class, userId);
        return count != null && count > 0;
    }

    public void insert(AgentProfile profile) {
        jdbcTemplate.update(
                "INSERT INTO agent_profiles (user_id, max_concurrent, availability) VALUES (?, ?, ?)",
                profile.getUserId(), profile.getMaxConcurrent(), profile.getAvailability());
    }

    public int update(AgentProfile profile) {
        return jdbcTemplate.update(
                "UPDATE agent_profiles SET max_concurrent = ?, availability = ? WHERE user_id = ?",
                profile.getMaxConcurrent(), profile.getAvailability(), profile.getUserId());
    }
}

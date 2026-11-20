package com.serviceflow.ai.dispatch.repository;

import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.entity.AgentSkillRelation;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** 技能字典与人员技能关系数据访问。 */
@Repository
public class AgentSkillRepository {

    private static final RowMapper<AgentSkill> SKILL_MAPPER = new BeanPropertyRowMapper<>(AgentSkill.class);
    private static final RowMapper<AgentSkillRelation> RELATION_MAPPER =
            new BeanPropertyRowMapper<>(AgentSkillRelation.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentSkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AgentSkill> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM agent_skills ORDER BY id ASC", SKILL_MAPPER);
    }

    public Optional<AgentSkill> findById(Long id) {
        List<AgentSkill> list = jdbcTemplate.query(
                "SELECT * FROM agent_skills WHERE id = ?", SKILL_MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<AgentSkill> findByCode(String skillCode) {
        List<AgentSkill> list = jdbcTemplate.query(
                "SELECT * FROM agent_skills WHERE skill_code = ?", SKILL_MAPPER, skillCode);
        return list.stream().findFirst();
    }

    public Optional<AgentSkill> findByName(String skillName) {
        List<AgentSkill> list = jdbcTemplate.query(
                "SELECT * FROM agent_skills WHERE skill_name = ?", SKILL_MAPPER, skillName);
        return list.stream().findFirst();
    }

    public AgentSkill insert(AgentSkill skill) {
        String sql = "INSERT INTO agent_skills (skill_code, skill_name, description) VALUES (?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, skill.getSkillCode());
            ps.setString(2, skill.getSkillName());
            ps.setString(3, skill.getDescription());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            skill.setId(key.longValue());
        }
        return skill;
    }

    public List<AgentSkillRelation> findRelationsByAgentId(Long agentId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_skill_relations WHERE agent_id = ? ORDER BY skill_id ASC",
                RELATION_MAPPER, agentId);
    }

    public List<AgentSkillRelation> findRelationsByAgentIds(Collection<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(agentIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT * FROM agent_skill_relations WHERE agent_id IN (" + placeholders + ") ORDER BY agent_id, skill_id",
                RELATION_MAPPER, agentIds.toArray());
    }

    public void deleteRelationsByAgentId(Long agentId) {
        jdbcTemplate.update("DELETE FROM agent_skill_relations WHERE agent_id = ?", agentId);
    }

    public void insertRelation(Long agentId, Long skillId, int proficiencyLevel) {
        jdbcTemplate.update(
                "INSERT INTO agent_skill_relations (agent_id, skill_id, proficiency_level) VALUES (?, ?, ?)",
                agentId, skillId, proficiencyLevel);
    }
}

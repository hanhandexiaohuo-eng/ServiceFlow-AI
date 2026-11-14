package com.serviceflow.ai.auth.repository;

import com.serviceflow.ai.auth.entity.User;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问层：沿用 JdbcTemplate + 预编译占位符风格。
 */
@Repository
public class UserRepository {

    private static final RowMapper<User> ROW_MAPPER = new BeanPropertyRowMapper<>(User.class);

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findById(Long id) {
        List<User> list = jdbcTemplate.query(
                "SELECT * FROM users WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        List<User> list = jdbcTemplate.query(
                "SELECT * FROM users WHERE username = ?", ROW_MAPPER, username);
        return list.stream().findFirst();
    }

    public long count() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return total != null ? total : 0;
    }

    /** 插入并回填自增主键 */
    public User insert(User u) {
        String sql = "INSERT INTO users (username, password, name, role, status) VALUES (?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getPassword());
            ps.setString(3, u.getName());
            ps.setString(4, u.getRole());
            ps.setInt(5, u.getStatus());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            u.setId(key.longValue());
        }
        return u;
    }
}

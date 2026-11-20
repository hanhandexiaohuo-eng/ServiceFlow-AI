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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /** 分配处理人候选：只返回启用账号，按显示名排序 */
    public List<User> findAllActive() {
        return jdbcTemplate.query(
                "SELECT * FROM users WHERE status = 1 ORDER BY id ASC", ROW_MAPPER);
    }

    /** 全部用户（含禁用），人员管理页需要展示账号状态 */
    public List<User> findAll() {
        return jdbcTemplate.query("SELECT * FROM users ORDER BY id ASC", ROW_MAPPER);
    }

    public List<User> findByRole(String role) {
        return jdbcTemplate.query(
                "SELECT * FROM users WHERE role = ? ORDER BY id ASC", ROW_MAPPER, role);
    }

    public int updateRole(Long id, String role) {
        return jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, id);
    }

    public boolean existsByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username);
        return count != null && count > 0;
    }

    /** 用户管理分页查询：关键字匹配登录名或显示名，可选角色与状态 */
    public List<User> search(String keyword, String role, Integer status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildSearchWhere(keyword, role, status, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM users WHERE 1 = 1" + where + " ORDER BY id ASC LIMIT ? OFFSET ?",
                ROW_MAPPER, args.toArray());
    }

    public long countSearch(String keyword, String role, Integer status) {
        List<Object> args = new ArrayList<>();
        String where = buildSearchWhere(keyword, role, status, args);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE 1 = 1" + where, Long.class, args.toArray());
        return total != null ? total : 0;
    }

    private String buildSearchWhere(String keyword, String role, Integer status, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (username LIKE ? OR name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND role = ?");
            args.add(role);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        return where.toString();
    }

    public int updateStatus(Long id, int status) {
        return jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", status, id);
    }

    public int updateProfile(Long id, String name, String department) {
        return jdbcTemplate.update(
                "UPDATE users SET name = ?, department = ? WHERE id = ?", name, department, id);
    }

    /** 角色变更：同时提升会话版本，旧权限立即失效 */
    public int updateRoleAndInvalidate(Long id, String role) {
        return jdbcTemplate.update(
                "UPDATE users SET role = ?, token_version = token_version + 1 WHERE id = ?", role, id);
    }

    /** 管理员重置密码：强制下次登录改密，并让旧会话立即失效 */
    public int resetPassword(Long id, String passwordHash) {
        return jdbcTemplate.update(
                "UPDATE users SET password = ?, password_change_required = 1, failed_login_count = 0, "
                        + "locked_until = NULL, token_version = token_version + 1 WHERE id = ?",
                passwordHash, id);
    }

    /** 统计启用状态的管理员，用于阻止禁用/降级最后一个管理员 */
    public long countActiveAdmins() {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 1", Long.class);
        return total != null ? total : 0;
    }

    public long count() {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return total != null ? total : 0;
    }

    /** 登录成功：记录时间并清零失败计数/锁定 */
    public int recordLoginSuccess(Long id) {
        return jdbcTemplate.update(
                "UPDATE users SET last_login_at = NOW(), failed_login_count = 0, locked_until = NULL WHERE id = ?", id);
    }

    /** 登录失败：累计失败次数，达到阈值时写入锁定截止时间 */
    public int recordLoginFailure(Long id, int failedCount, LocalDateTime lockedUntil) {
        return jdbcTemplate.update(
                "UPDATE users SET failed_login_count = ?, locked_until = ? WHERE id = ?",
                failedCount,
                lockedUntil == null ? null : Timestamp.valueOf(lockedUntil),
                id);
    }

    /** 会话版本 +1：旧 Token 立即失效 */
    public int incrementTokenVersion(Long id) {
        return jdbcTemplate.update(
                "UPDATE users SET token_version = token_version + 1 WHERE id = ?", id);
    }

    /** 更新密码哈希并清除首次改密标记（同时 +1 会话版本） */
    public int updatePassword(Long id, String passwordHash) {
        return jdbcTemplate.update(
                "UPDATE users SET password = ?, password_change_required = 0, failed_login_count = 0, "
                        + "locked_until = NULL, token_version = token_version + 1 WHERE id = ?",
                passwordHash, id);
    }

    public int markPasswordChangeRequired(Long id) {
        return jdbcTemplate.update(
                "UPDATE users SET password_change_required = 1 WHERE id = ?", id);
    }

    /** 插入并回填自增主键 */
    public User insert(User u) {
        String sql = "INSERT INTO users (username, password, name, role, status, department) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getPassword());
            ps.setString(3, u.getName());
            ps.setString(4, u.getRole());
            ps.setInt(5, u.getStatus());
            ps.setString(6, u.getDepartment());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            u.setId(key.longValue());
        }
        return u;
    }
}

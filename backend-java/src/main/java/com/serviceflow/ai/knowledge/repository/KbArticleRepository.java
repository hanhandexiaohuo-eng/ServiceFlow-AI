package com.serviceflow.ai.knowledge.repository;

import com.serviceflow.ai.knowledge.entity.KbArticle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** 知识库文章数据访问：关键词检索（LIKE），不使用向量索引。 */
@Repository
public class KbArticleRepository {

    private static final String BASE_SELECT = """
            SELECT a.*, cu.name AS created_by_name, uu.name AS updated_by_name
            FROM kb_articles a
            LEFT JOIN users cu ON cu.id = a.created_by
            LEFT JOIN users uu ON uu.id = a.updated_by
            """;

    private static final RowMapper<KbArticle> MAPPER = (rs, rowNum) -> {
        KbArticle a = new KbArticle();
        a.setId(rs.getLong("id"));
        a.setTitle(rs.getString("title"));
        a.setCategory(rs.getString("category"));
        a.setProblem(rs.getString("problem"));
        a.setSolution(rs.getString("solution"));
        a.setTechStack(rs.getString("tech_stack"));
        a.setKeywords(rs.getString("keywords"));
        long sourceTicketId = rs.getLong("source_ticket_id");
        a.setSourceTicketId(rs.wasNull() ? null : sourceTicketId);
        a.setStatus(rs.getString("status"));
        long createdBy = rs.getLong("created_by");
        a.setCreatedBy(rs.wasNull() ? null : createdBy);
        long updatedBy = rs.getLong("updated_by");
        a.setUpdatedBy(rs.wasNull() ? null : updatedBy);
        a.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        a.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        a.setCreatedByName(rs.getString("created_by_name"));
        a.setUpdatedByName(rs.getString("updated_by_name"));
        return a;
    };

    /** 检索命中：hits 用于计算可解释的相关度。 */
    public record ArticleHit(KbArticle article, int hits) {
    }

    private final JdbcTemplate jdbcTemplate;

    public KbArticleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public KbArticle insert(KbArticle article) {
        String sql = """
                INSERT INTO kb_articles
                    (title, category, problem, solution, tech_stack, keywords, source_ticket_id,
                     status, created_by, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, article.getTitle());
            ps.setString(2, article.getCategory());
            ps.setString(3, article.getProblem());
            ps.setString(4, article.getSolution());
            ps.setString(5, article.getTechStack());
            ps.setString(6, article.getKeywords());
            if (article.getSourceTicketId() == null) {
                ps.setObject(7, null);
            } else {
                ps.setLong(7, article.getSourceTicketId());
            }
            ps.setString(8, article.getStatus());
            ps.setObject(9, article.getCreatedBy());
            ps.setObject(10, article.getUpdatedBy());
            ps.setObject(11, now);
            ps.setObject(12, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            article.setId(key.longValue());
        }
        return article;
    }

    public void update(KbArticle article) {
        jdbcTemplate.update("""
                        UPDATE kb_articles
                        SET title = ?, category = ?, problem = ?, solution = ?, tech_stack = ?,
                            keywords = ?, source_ticket_id = ?, status = ?, updated_by = ?, updated_at = ?
                        WHERE id = ?
                        """,
                article.getTitle(), article.getCategory(), article.getProblem(), article.getSolution(),
                article.getTechStack(), article.getKeywords(), article.getSourceTicketId(),
                article.getStatus(), article.getUpdatedBy(), LocalDateTime.now(), article.getId());
    }

    public Optional<KbArticle> findById(Long id) {
        List<KbArticle> list = jdbcTemplate.query(BASE_SELECT + " WHERE a.id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<KbArticle> findPage(String keyword, String category, String status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(keyword, category, status, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                BASE_SELECT + where + " ORDER BY a.updated_at DESC, a.id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
    }

    public long count(String keyword, String category, String status) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(keyword, category, status, args);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_articles a " + where,
                Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String buildWhere(String keyword, String category, String status, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            conditions.add("a.status = ?");
            args.add(status);
        }
        if (category != null && !category.isBlank()) {
            conditions.add("a.category = ?");
            args.add(category);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            conditions.add("(a.title LIKE ? OR a.problem LIKE ? OR a.solution LIKE ? OR a.keywords LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /** 按关键词命中数量排序，返回真实文章与命中数。 */
    public List<ArticleHit> searchByKeywords(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder score = new StringBuilder("(");
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < keywords.size(); i++) {
            String like = "%" + keywords.get(i) + "%";
            if (i > 0) {
                score.append(" + ");
                where.append(" OR ");
            }
            score.append("(a.title LIKE ?) + (a.problem LIKE ?) + (a.keywords LIKE ?)");
            where.append("(a.title LIKE ? OR a.problem LIKE ? OR a.keywords LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        score.append(")");
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.addAll(args);
        queryArgs.add(limit);

        String sql = "SELECT a.*, cu.name AS created_by_name, uu.name AS updated_by_name, "
                + score + " AS hit_score "
                + "FROM kb_articles a "
                + "LEFT JOIN users cu ON cu.id = a.created_by "
                + "LEFT JOIN users uu ON uu.id = a.updated_by "
                + "WHERE a.status = 'PUBLISHED' AND (" + where + ") "
                + "ORDER BY hit_score DESC, a.updated_at DESC LIMIT ?";

        RowMapper<ArticleHit> mapper = (rs, rowNum) -> {
            KbArticle article = MAPPER.mapRow(rs, rowNum);
            return new ArticleHit(article, rs.getInt("hit_score"));
        };
        return jdbcTemplate.query(sql, mapper, queryArgs.toArray());
    }

    public List<KbArticle> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(BASE_SELECT + " WHERE a.id IN (" + placeholders + ")",
                MAPPER, ids.toArray());
    }
}

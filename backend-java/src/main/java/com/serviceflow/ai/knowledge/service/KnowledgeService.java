package com.serviceflow.ai.knowledge.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.knowledge.dto.KnowledgeLinkRequest;
import com.serviceflow.ai.knowledge.dto.KnowledgeSaveRequest;
import com.serviceflow.ai.knowledge.entity.KbArticle;
import com.serviceflow.ai.knowledge.entity.KbTicketLink;
import com.serviceflow.ai.knowledge.repository.KbArticleRepository;
import com.serviceflow.ai.knowledge.repository.KbTicketLinkRepository;
import com.serviceflow.ai.knowledge.vo.KnowledgeArticleResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeCategoryResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeLinkResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeRecommendationResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeSummaryResponse;
import com.serviceflow.ai.knowledge.vo.LinkedTicketResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 知识库：关键词检索 + 与工单关联；不实现向量检索。 */
@Service
public class KnowledgeService {

    public static final Set<String> CATEGORIES = Set.of(
            "NETWORK", "SYSTEM", "DATABASE", "SECURITY", "SOFTWARE", "ACCOUNT", "HARDWARE", "OTHER");
    private static final Set<String> STATUSES = Set.of("PUBLISHED", "ARCHIVED");

    private final KbArticleRepository articleRepository;
    private final KbTicketLinkRepository linkRepository;
    private final AiTicketAccessGuard guard;

    public KnowledgeService(KbArticleRepository articleRepository,
                            KbTicketLinkRepository linkRepository,
                            AiTicketAccessGuard guard) {
        this.articleRepository = articleRepository;
        this.linkRepository = linkRepository;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public PageResponse<KnowledgeSummaryResponse> page(String keyword, String category, String status,
                                                       int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String safeCategory = category == null || category.isBlank()
                ? null : normalizeCategory(category);
        String safeStatus = status == null || status.isBlank() ? null : normalizeStatus(status);
        List<KnowledgeSummaryResponse> list = articleRepository
                .findPage(keyword, safeCategory, safeStatus, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toSummary).toList();
        long total = articleRepository.count(keyword, safeCategory, safeStatus);
        return new PageResponse<>(list, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public KnowledgeArticleResponse get(Long id) {
        return toDetail(requireArticle(id));
    }

    @Transactional(readOnly = true)
    public List<LinkedTicketResponse> linkedTickets(Long articleId) {
        requireArticle(articleId);
        return linkRepository.findByArticleId(articleId);
    }

    @Transactional
    public KnowledgeArticleResponse create(KnowledgeSaveRequest req) {
        guard.requireAdmin();
        LoginUser user = guard.currentUser();
        if (req.sourceTicketId() != null) {
            guard.requireAccessible(req.sourceTicketId());
        }
        KbArticle article = new KbArticle();
        apply(article, req);
        article.setCreatedBy(user.id());
        article.setUpdatedBy(user.id());
        articleRepository.insert(article);
        return get(article.getId());
    }

    @Transactional
    public KnowledgeArticleResponse update(Long id, KnowledgeSaveRequest req) {
        guard.requireAdmin();
        KbArticle article = requireArticle(id);
        if (req.sourceTicketId() != null) {
            guard.requireAccessible(req.sourceTicketId());
        }
        apply(article, req);
        article.setUpdatedBy(guard.currentUser().id());
        articleRepository.update(article);
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeCategoryResponse> categories() {
        Map<String, Long> counts = new LinkedHashMap<>();
        articleRepository.findPage(null, null, "PUBLISHED", 0, 1000)
                .forEach(a -> counts.merge(a.getCategory(), 1L, Long::sum));
        return counts.entrySet().stream()
                .map(e -> new KnowledgeCategoryResponse(e.getKey(), e.getValue()))
                .toList();
    }

    @Transactional
    public KnowledgeLinkResponse link(Long articleId, KnowledgeLinkRequest req) {
        LoginUser user = guard.currentUser();
        if (user.role() == UserRole.USER) {
            throw new ForbiddenException("仅管理员或处理人员可关联知识");
        }
        requireArticle(articleId);
        guard.requireAccessible(req.ticketId());
        if (linkRepository.exists(articleId, req.ticketId())) {
            throw new ConflictException("该知识已关联此工单");
        }
        KbTicketLink link = new KbTicketLink();
        link.setArticleId(articleId);
        link.setTicketId(req.ticketId());
        link.setLinkedBy(user.id());
        link.setNote(req.note());
        try {
            linkRepository.insert(link);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("该知识已关联此工单");
        }
        return linkRepository.findByTicketId(req.ticketId()).stream()
                .filter(item -> item.articleId().equals(articleId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("知识关联失败"));
    }

    @Transactional
    public void unlink(Long articleId, Long ticketId) {
        guard.requireAdmin();
        int affected = linkRepository.delete(articleId, ticketId);
        if (affected == 0) {
            throw new NotFoundException("知识关联不存在");
        }
    }

    @Transactional(readOnly = true)
    public List<KnowledgeLinkResponse> linksForTicket(Long ticketId) {
        guard.requireAccessible(ticketId);
        return linkRepository.findByTicketId(ticketId);
    }

    /** 根据工单与 AI 关键词检索真实知识，并按命中数计算可解释相关度。 */
    @Transactional(readOnly = true)
    public List<KnowledgeRecommendationResponse> recommendForTicket(Ticket ticket, List<String> extraKeywords,
                                                                    int limit) {
        List<String> keywords = buildKeywords(ticket, extraKeywords);
        if (keywords.isEmpty()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(10, limit));
        return articleRepository.searchByKeywords(keywords, safeLimit).stream()
                .map(hit -> toRecommendation(hit, keywords))
                .toList();
    }

    /** 传给 Python 的候选知识：只包含真实记录的精简字段。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> promptCandidates(Ticket ticket, int limit) {
        List<String> keywords = buildKeywords(ticket, List.of());
        if (keywords.isEmpty()) {
            return List.of();
        }
        return articleRepository.searchByKeywords(keywords, Math.max(1, Math.min(5, limit))).stream()
                .map(hit -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    KbArticle a = hit.article();
                    map.put("articleId", a.getId());
                    map.put("title", a.getTitle());
                    map.put("category", a.getCategory());
                    map.put("keywords", a.getKeywords());
                    map.put("problemSummary", clip(a.getProblem(), 160));
                    return map;
                })
                .toList();
    }

    private KnowledgeRecommendationResponse toRecommendation(KbArticleRepository.ArticleHit hit,
                                                             List<String> keywords) {
        KbArticle a = hit.article();
        List<String> matched = keywords.stream()
                .filter(k -> containsIgnoreCase(a.getTitle(), k)
                        || containsIgnoreCase(a.getKeywords(), k)
                        || containsIgnoreCase(a.getProblem(), k))
                .distinct()
                .limit(5)
                .toList();
        int relevance = Math.min(99, 50 + hit.hits() * 8);
        String reason = matched.isEmpty()
                ? "标题或关键词存在匹配"
                : "命中关键词：" + String.join("、", matched);
        return new KnowledgeRecommendationResponse(
                a.getId(), a.getTitle(), a.getCategory(), clip(a.getProblem(), 120),
                clip(a.getSolution(), 200), a.getKeywords(), relevance, reason, matched);
    }

    private List<String> buildKeywords(Ticket ticket, List<String> extra) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (extra != null) {
            extra.forEach(item -> tokens.addAll(tokenize(item)));
        }
        tokens.addAll(tokenize(ticket.getTitle()));
        if (tokens.size() < 4) {
            tokens.addAll(tokenize(clip(ticket.getDescription(), 40)));
        }
        return tokens.stream().limit(8).toList();
    }

    /** 中文按标点切分后补充二元组，提升 LIKE 关键词召回。 */
    List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] segments = text.split("[\\s,，。;；、！？!?/|:：()（）\\[\\]【】\"'“”]+");
        for (String raw : segments) {
            String segment = raw.replace("%", "").replace("_", "").trim();
            if (segment.length() < 2) {
                continue;
            }
            if (segment.length() <= 20) {
                tokens.add(segment);
            }
            if (containsCjk(segment)) {
                int added = 0;
                for (int i = 0; i + 2 <= segment.length() && added < 4; i++) {
                    String bigram = segment.substring(i, i + 2);
                    if (containsCjk(bigram)) {
                        tokens.add(bigram);
                        added++;
                    }
                }
            }
        }
        return tokens.stream().distinct().toList();
    }

    private boolean containsCjk(String value) {
        return value.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        return source != null && keyword != null
                && source.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private void apply(KbArticle article, KnowledgeSaveRequest req) {
        article.setTitle(req.title().trim());
        article.setCategory(normalizeCategory(req.category()));
        article.setProblem(req.problem().trim());
        article.setSolution(req.solution().trim());
        article.setTechStack(req.techStack() == null ? null : req.techStack().trim());
        article.setKeywords(req.keywords().trim());
        article.setSourceTicketId(req.sourceTicketId());
        article.setStatus(req.status() == null || req.status().isBlank()
                ? "PUBLISHED" : normalizeStatus(req.status()));
    }

    private String normalizeCategory(String category) {
        String value = category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(value)) {
            throw new IllegalArgumentException("知识分类不合法：" + category);
        }
        return value;
    }

    private String normalizeStatus(String status) {
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new IllegalArgumentException("知识状态不合法：" + status);
        }
        return value;
    }

    private KbArticle requireArticle(Long id) {
        return articleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("知识文章不存在：id=" + id));
    }

    private KnowledgeSummaryResponse toSummary(KbArticle a) {
        return new KnowledgeSummaryResponse(
                a.getId(), a.getTitle(), a.getCategory(), a.getKeywords(), a.getStatus(),
                a.getUpdatedByName(), a.getUpdatedAt());
    }

    private KnowledgeArticleResponse toDetail(KbArticle a) {
        return new KnowledgeArticleResponse(
                a.getId(), a.getTitle(), a.getCategory(), a.getProblem(), a.getSolution(),
                a.getTechStack(), a.getKeywords(), a.getSourceTicketId(), a.getStatus(),
                a.getCreatedByName(), a.getUpdatedByName(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}

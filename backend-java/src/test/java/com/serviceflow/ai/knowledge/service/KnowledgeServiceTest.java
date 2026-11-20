package com.serviceflow.ai.knowledge.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
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
import com.serviceflow.ai.knowledge.vo.KnowledgeLinkResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeSummaryResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库业务测试（纯单元，mock 仓储）：
 * 覆盖检索参数透传与空结果、详情/编辑缺失、创建分类与状态归一、
 * 以及关联/解除关联的权限、重复与缺失分支。
 * 访问控制使用真实 AiTicketAccessGuard（仅 mock 其 TicketRepository），
 * 以便验证真实的角色判断而不是 mock 掉权限。
 */
class KnowledgeServiceTest {

    private final KbArticleRepository articleRepository = mock(KbArticleRepository.class);
    private final KbTicketLinkRepository linkRepository = mock(KbTicketLinkRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final AiTicketAccessGuard guard = new AiTicketAccessGuard(ticketRepository);
    private final KnowledgeService knowledgeService =
            new KnowledgeService(articleRepository, linkRepository, guard);

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void loginAs(UserRole role) {
        UserContext.set(new LoginUser(1L, "tester", "张三", role));
    }

    private KbArticle article(long id) {
        KbArticle a = new KbArticle();
        a.setId(id);
        a.setTitle("VPN 无法连接");
        a.setCategory("NETWORK");
        a.setProblem("客户端连接超时");
        a.setSolution("重置证书后重连");
        a.setKeywords("vpn,连接");
        a.setStatus("PUBLISHED");
        a.setUpdatedAt(LocalDateTime.of(2026, 11, 1, 10, 0));
        return a;
    }

    private KnowledgeSaveRequest request(String category, String status) {
        return new KnowledgeSaveRequest("标题", category, "问题", "方案", "Java", "关键词", null, status);
    }

    @Test
    void pagePassesNormalizedFiltersAndReturnsEmptyResult() {
        when(articleRepository.findPage("vpn", "NETWORK", "PUBLISHED", 10, 5)).thenReturn(List.of());
        when(articleRepository.count("vpn", "NETWORK", "PUBLISHED")).thenReturn(0L);

        PageResponse<KnowledgeSummaryResponse> page =
                knowledgeService.page("vpn", " network ", " published ", 3, 5);

        assertTrue(page.list().isEmpty());
        assertEquals(0L, page.total());
        assertEquals(3, page.page());
        assertEquals(5, page.size());
        verify(articleRepository).findPage("vpn", "NETWORK", "PUBLISHED", 10, 5);
        verify(articleRepository).count("vpn", "NETWORK", "PUBLISHED");
    }

    @Test
    void pageClampsPageAndSizeToSafeRange() {
        when(articleRepository.findPage(null, null, null, 0, 100))
                .thenReturn(List.of(article(1L)));
        when(articleRepository.count(null, null, null)).thenReturn(1L);

        PageResponse<KnowledgeSummaryResponse> page = knowledgeService.page(null, null, null, 0, 999);

        assertEquals(1, page.list().size());
        assertEquals("VPN 无法连接", page.list().get(0).title());
        assertEquals(1, page.page());
        assertEquals(100, page.size());
        verify(articleRepository).findPage(null, null, null, 0, 100);
    }

    @Test
    void getMissingArticleThrowsNotFound() {
        when(articleRepository.findById(99L)).thenReturn(Optional.empty());

        NotFoundException e = assertThrows(NotFoundException.class, () -> knowledgeService.get(99L));
        assertEquals("知识文章不存在：id=99", e.getMessage());
    }

    @Test
    void createNormalizesCategoryAndDefaultsStatusToPublished() {
        loginAs(UserRole.ADMIN);
        KbArticle[] stored = new KbArticle[1];
        when(articleRepository.insert(any(KbArticle.class))).thenAnswer(inv -> {
            KbArticle saved = inv.getArgument(0);
            saved.setId(7L);
            stored[0] = saved;
            return saved;
        });
        when(articleRepository.findById(7L)).thenAnswer(inv -> Optional.ofNullable(stored[0]));

        KnowledgeArticleResponse resp = knowledgeService.create(request(" network ", null));

        assertEquals(7L, resp.id());
        assertEquals("NETWORK", resp.category());
        assertEquals("PUBLISHED", resp.status());
        ArgumentCaptor<KbArticle> captor = ArgumentCaptor.forClass(KbArticle.class);
        verify(articleRepository).insert(captor.capture());
        assertEquals(1L, captor.getValue().getCreatedBy());
        assertEquals(1L, captor.getValue().getUpdatedBy());
        assertEquals("Java", captor.getValue().getTechStack());
    }

    @Test
    void createWithUnknownCategoryIsRejectedBeforeInsert() {
        loginAs(UserRole.ADMIN);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> knowledgeService.create(request("NOT_A_CATEGORY", null)));
        assertEquals("知识分类不合法：NOT_A_CATEGORY", e.getMessage());
        verify(articleRepository, never()).insert(any());
    }

    @Test
    void createByNonAdminIsForbidden() {
        loginAs(UserRole.AGENT);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> knowledgeService.create(request("NETWORK", null)));
        assertEquals("仅管理员可执行该操作", e.getMessage());
        verify(articleRepository, never()).insert(any());
    }

    @Test
    void updateMissingArticleThrowsNotFound() {
        loginAs(UserRole.ADMIN);
        when(articleRepository.findById(88L)).thenReturn(Optional.empty());

        NotFoundException e = assertThrows(NotFoundException.class,
                () -> knowledgeService.update(88L, request("NETWORK", null)));
        assertEquals("知识文章不存在：id=88", e.getMessage());
        verify(articleRepository, never()).update(any());
    }

    @Test
    void linkByNormalUserIsForbidden() {
        loginAs(UserRole.USER);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> knowledgeService.link(7L, new KnowledgeLinkRequest(3L, null)));
        assertEquals("仅管理员或处理人员可关联知识", e.getMessage());
        verify(articleRepository, never()).findById(any());
    }

    @Test
    void linkDuplicateRelationThrowsConflict() {
        loginAs(UserRole.AGENT);
        Ticket ticket = new Ticket();
        ticket.setId(3L);
        ticket.setAssigneeId(1L);
        when(articleRepository.findById(7L)).thenReturn(Optional.of(article(7L)));
        when(ticketRepository.findById(3L)).thenReturn(Optional.of(ticket));
        when(linkRepository.exists(7L, 3L)).thenReturn(true);

        ConflictException e = assertThrows(ConflictException.class,
                () -> knowledgeService.link(7L, new KnowledgeLinkRequest(3L, "参考")));
        assertEquals("该知识已关联此工单", e.getMessage());
        verify(linkRepository, never()).insert(any());
    }

    @Test
    void linkSuccessReturnsCreatedRelation() {
        loginAs(UserRole.AGENT);
        Ticket ticket = new Ticket();
        ticket.setId(3L);
        ticket.setAssigneeId(1L);
        LocalDateTime linkedAt = LocalDateTime.of(2026, 11, 2, 9, 0);
        when(articleRepository.findById(7L)).thenReturn(Optional.of(article(7L)));
        when(ticketRepository.findById(3L)).thenReturn(Optional.of(ticket));
        when(linkRepository.exists(7L, 3L)).thenReturn(false);
        when(linkRepository.findByTicketId(3L)).thenReturn(List.of(
                new KnowledgeLinkResponse(7L, "VPN 无法连接", "NETWORK", "vpn,连接",
                        "PUBLISHED", linkedAt, "张三", "参考")));

        KnowledgeLinkResponse resp = knowledgeService.link(7L, new KnowledgeLinkRequest(3L, "参考"));

        assertEquals(7L, resp.articleId());
        assertEquals("参考", resp.note());
        ArgumentCaptor<KbTicketLink> captor = ArgumentCaptor.forClass(KbTicketLink.class);
        verify(linkRepository).insert(captor.capture());
        assertEquals(1L, captor.getValue().getLinkedBy());
    }

    @Test
    void unlinkMissingRelationThrowsNotFound() {
        loginAs(UserRole.ADMIN);
        when(linkRepository.delete(7L, 3L)).thenReturn(0);

        NotFoundException e = assertThrows(NotFoundException.class,
                () -> knowledgeService.unlink(7L, 3L));
        assertEquals("知识关联不存在", e.getMessage());
    }

    @Test
    void unlinkExistingRelationDeletesIt() {
        loginAs(UserRole.ADMIN);
        when(linkRepository.delete(7L, 3L)).thenReturn(1);

        knowledgeService.unlink(7L, 3L);

        verify(linkRepository).delete(7L, 3L);
    }
}

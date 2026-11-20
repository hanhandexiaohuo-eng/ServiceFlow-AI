package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.dto.AiSessionCreateRequest;
import com.serviceflow.ai.intelligence.dto.AiSessionUpdateRequest;
import com.serviceflow.ai.intelligence.entity.AiAnalysis;
import com.serviceflow.ai.intelligence.entity.AiMessage;
import com.serviceflow.ai.intelligence.entity.AiSession;
import com.serviceflow.ai.intelligence.enums.AnalysisType;
import com.serviceflow.ai.intelligence.enums.SessionStatus;
import com.serviceflow.ai.intelligence.repository.AiAnalysisRepository;
import com.serviceflow.ai.intelligence.repository.AiMessageRepository;
import com.serviceflow.ai.intelligence.repository.AiSessionRepository;
import com.serviceflow.ai.intelligence.vo.AiAnalysisBrief;
import com.serviceflow.ai.intelligence.vo.AiChatResponse;
import com.serviceflow.ai.intelligence.vo.AiMessageResponse;
import com.serviceflow.ai.intelligence.vo.AiSessionDetailResponse;
import com.serviceflow.ai.intelligence.vo.AiSessionResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** AI 会话：归属人校验 + 工单可访问校验；追问失败不写入伪造的 ASSISTANT 消息。 */
@Service
public class AiSessionService {

    private final AiSessionRepository sessionRepository;
    private final AiMessageRepository messageRepository;
    private final AiAnalysisRepository analysisRepository;
    private final AiTicketAccessGuard guard;
    private final AiAnalysisService analysisService;
    private final AiRequestLimiter limiter;
    private final AiMapper mapper;

    public AiSessionService(AiSessionRepository sessionRepository,
                            AiMessageRepository messageRepository,
                            AiAnalysisRepository analysisRepository,
                            AiTicketAccessGuard guard,
                            AiAnalysisService analysisService,
                            AiRequestLimiter limiter,
                            AiMapper mapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.analysisRepository = analysisRepository;
        this.guard = guard;
        this.analysisService = analysisService;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Transactional
    public AiSessionResponse create(AiSessionCreateRequest req) {
        Ticket ticket = guard.requireAccessible(req.ticketId());
        LoginUser user = guard.currentUser();
        AiSession session = new AiSession();
        session.setTicketId(ticket.getId());
        session.setUserId(user.id());
        String title = req.title() == null || req.title().isBlank()
                ? ticket.getTicketNo() + " " + ticket.getTitle()
                : req.title().trim();
        session.setTitle(clip(title, 120));
        session.setStatus(SessionStatus.ACTIVE.name());
        sessionRepository.insert(session);
        return mapper.toSession(sessionRepository.findById(session.getId()).orElse(session));
    }

    @Transactional(readOnly = true)
    public PageResponse<AiSessionResponse> page(Long ticketId, String status, int page, int size) {
        LoginUser user = guard.currentUser();
        if (ticketId != null) {
            guard.requireAccessible(ticketId);
        }
        Long userId = user.role() == UserRole.ADMIN ? null : user.id();
        String safeStatus = normalizeStatus(status);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<AiSessionResponse> list = sessionRepository
                .findPage(userId, ticketId, safeStatus, (safePage - 1) * safeSize, safeSize)
                .stream().map(mapper::toSession).toList();
        long total = sessionRepository.count(userId, ticketId, safeStatus);
        return new PageResponse<>(list, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public AiSessionDetailResponse get(Long id) {
        AiSession session = requireOwned(id);
        List<AiMessageResponse> messages = messageRepository.findBySessionId(id)
                .stream().map(mapper::toMessage).toList();
        List<AiAnalysisBrief> analyses = new ArrayList<>();
        analysisRepository.findBySessionId(id, 20).forEach(a -> analyses.add(mapper.toBrief(a)));
        return new AiSessionDetailResponse(mapper.toSession(session), messages, analyses);
    }

    @Transactional
    public AiSessionResponse update(Long id, AiSessionUpdateRequest req) {
        requireOwned(id);
        if (req.title() != null && !req.title().isBlank()) {
            sessionRepository.updateTitle(id, clip(req.title().trim(), 120));
        }
        if (req.status() != null && !req.status().isBlank()) {
            sessionRepository.updateStatus(id, SessionStatus.parse(req.status()).name());
        }
        return mapper.toSession(sessionRepository.findById(id).orElseThrow(
                () -> new NotFoundException("AI 会话不存在：id=" + id)));
    }

    @Transactional
    public void delete(Long id) {
        requireOwned(id);
        int affected = sessionRepository.delete(id);
        if (affected == 0) {
            throw new NotFoundException("AI 会话不存在：id=" + id);
        }
    }

    /**
     * 会话内追问。先写 USER 消息再调用模型；模型失败时仅保留用户提问并抛出错误，
     * 绝不写入伪造的 ASSISTANT 回复。
     */
    public AiChatResponse ask(Long id, String content) {
        AiSession session = requireOwned(id);
        if (!SessionStatus.ACTIVE.name().equals(session.getStatus())) {
            throw new ConflictException("会话已归档，不能继续追问");
        }
        Ticket ticket = guard.requireAccessible(session.getTicketId());
        limiter.check(guard.currentUser().id());

        AiMessage userMessage = new AiMessage();
        userMessage.setSessionId(id);
        userMessage.setRole("USER");
        userMessage.setContent(clip(content, 8000));
        messageRepository.insert(userMessage);

        AiAnalysis analysis = analysisService.execute(AnalysisType.CHAT, ticket, id, content);
        JsonNode result = mapper.readJson(analysis.getResultJson());

        AiMessage assistant = new AiMessage();
        assistant.setSessionId(id);
        assistant.setRole("ASSISTANT");
        assistant.setContent(clip(result.path("answer").asText(""), 8000));
        assistant.setAnalysisId(analysis.getId());
        messageRepository.insert(assistant);
        sessionRepository.touch(id);

        return new AiChatResponse(
                mapper.toMessage(messageRepository.findById(userMessage.getId())),
                mapper.toMessage(messageRepository.findById(assistant.getId())),
                analysisService.toResponse(analysis));
    }

    /** 归属人或管理员才能访问；同时要求工单本身对当前用户可访问。 */
    private AiSession requireOwned(Long id) {
        AiSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("AI 会话不存在：id=" + id));
        LoginUser user = guard.currentUser();
        if (user.role() != UserRole.ADMIN && !Objects.equals(session.getUserId(), user.id())) {
            throw new ForbiddenException("无权访问该 AI 会话");
        }
        guard.requireAccessible(session.getTicketId());
        return session;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return SessionStatus.parse(status).name();
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}

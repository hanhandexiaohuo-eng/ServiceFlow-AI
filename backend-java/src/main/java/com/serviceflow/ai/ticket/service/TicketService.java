package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.service.UserQueryService;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketEventResponse;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.entity.TicketEvent;
import com.serviceflow.ai.ticket.enums.Priority;
import com.serviceflow.ai.ticket.enums.TicketAction;
import com.serviceflow.ai.ticket.enums.TicketEventType;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import com.serviceflow.ai.ticket.enums.TicketType;
import com.serviceflow.ai.ticket.repository.TicketEventRepository;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.workflow.TicketWorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 工单业务层：创建、查询、内容编辑、删除与响应组装。
 * 状态流转不在这里，统一交给 TicketWorkflowService，避免出现第二条绕过状态机的写入口。
 */
@Service
public class TicketService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NO_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TicketRepository ticketRepository;
    private final TicketEventRepository eventRepository;
    private final TicketSlaService slaService;
    private final TicketWorkflowEngine workflowEngine;
    private final UserQueryService userQueryService;

    public TicketService(TicketRepository ticketRepository,
                         TicketEventRepository eventRepository,
                         TicketSlaService slaService,
                         TicketWorkflowEngine workflowEngine,
                         UserQueryService userQueryService) {
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.slaService = slaService;
        this.workflowEngine = workflowEngine;
        this.userQueryService = userQueryService;
    }

    @Transactional
    public TicketResponse create(TicketCreateRequest req) {
        LoginUser creator = UserContext.getRequired();
        LocalDateTime now = LocalDateTime.now();

        Ticket t = new Ticket();
        t.setTicketNo(generateTicketNo());
        t.setTitle(req.title());
        t.setDescription(req.description());
        t.setType(parseEnum(TicketType.class, req.type(), "工单类型").name());
        String priority = req.priority() == null || req.priority().isBlank()
                ? Priority.MEDIUM.name()
                : parseEnum(Priority.class, req.priority(), "优先级").name();
        t.setPriority(priority);
        t.setCreator(creator.name());
        t.setCreatorId(creator.id());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t.setSlaDueAt(slaService.dueAt(now, priority));

        // 创建时指定处理人 → 直接进入 ASSIGNED；否则 PENDING。处理人必须是管理员或处理人员。
        if (req.assignee() != null && !req.assignee().isBlank()) {
            User assignee = userQueryService.requireActiveAssigneeByName(req.assignee());
            t.setAssignee(assignee.getName());
            t.setAssigneeId(assignee.getId());
            t.setStatus(TicketStatus.ASSIGNED.name());
        } else {
            t.setStatus(TicketStatus.PENDING.name());
        }

        ticketRepository.insert(t);
        eventRepository.insert(buildEvent(t, TicketEventType.CREATE, null,
                TicketStatus.parse(t.getStatus()), creator, "创建工单"));
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> page(String status, String priority, int page, int size) {
        if (status != null && !status.isBlank()) {
            TicketStatus.parse(status);
        }
        if (priority != null && !priority.isBlank()) {
            parseEnum(Priority.class, priority, "优先级");
        }
        List<TicketResponse> list = ticketRepository.findPage(status, priority, page, size)
                .stream().map(this::toResponse).toList();
        long total = ticketRepository.count(status, priority);
        return new PageResponse<>(list, total, page, size);
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id) {
        return toResponse(requireTicket(id));
    }

    @Transactional
    public TicketResponse update(Long id, TicketUpdateRequest req) {
        // 旧客户端可能继续提交 status/assignee，这里显式拒绝而不是静默忽略
        if (req.status() != null && !req.status().isBlank()) {
            throw new IllegalArgumentException("状态变更请使用工作流接口（接单/开始处理/解决/关闭/取消）");
        }
        if (req.assignee() != null && !req.assignee().isBlank()) {
            throw new IllegalArgumentException("处理人变更请使用分配或转交接口");
        }

        Ticket t = requireTicket(id);
        LoginUser operator = UserContext.getRequired();
        if (!workflowEngine.canEdit(t, operator)) {
            throw new ForbiddenException("仅管理员、创建人或当前处理人可编辑工单");
        }
        if (TicketStatus.parse(t.getStatus()).terminal()) {
            throw new IllegalArgumentException("已结束工单不可编辑");
        }

        List<String> changed = new ArrayList<>();
        if (!Objects.equals(t.getTitle(), req.title())) {
            changed.add("标题");
        }
        if (!Objects.equals(t.getDescription(), req.description())) {
            changed.add("描述");
        }
        String type = parseEnum(TicketType.class, req.type(), "工单类型").name();
        if (!Objects.equals(t.getType(), type)) {
            changed.add("类型");
        }
        String priority = parseEnum(Priority.class, req.priority(), "优先级").name();
        if (!Objects.equals(t.getPriority(), priority)) {
            changed.add("优先级");
            t.setPriority(priority);
            t.setSlaDueAt(slaService.dueAt(t.getCreatedAt(), priority));
        }

        t.setTitle(req.title());
        t.setDescription(req.description());
        t.setType(type);
        ticketRepository.updateContent(t);

        if (!changed.isEmpty()) {
            eventRepository.insert(buildEvent(t, TicketEventType.UPDATE,
                    TicketStatus.parse(t.getStatus()), TicketStatus.parse(t.getStatus()),
                    operator, "修改字段：" + String.join("、", changed)));
        }
        return getById(id);
    }

    @Transactional
    public void delete(Long id) {
        if (UserContext.getRequired().role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可删除工单");
        }
        requireTicket(id);
        eventRepository.deleteByTicketId(id);
        int affected = ticketRepository.deleteById(id);
        if (affected == 0) {
            throw new NotFoundException("工单不存在：id=" + id);
        }
    }

    @Transactional(readOnly = true)
    public List<TicketEventResponse> listEvents(Long id) {
        requireTicket(id);
        return eventRepository.findByTicketId(id).stream().map(TicketEventResponse::from).toList();
    }

    /**
     * 统一响应组装：SLA 与允许动作都由服务端计算。
     * 前端隐藏按钮只是体验优化，真正的权限判断仍在工作流执行时发生。
     */
    public TicketResponse toResponse(Ticket t) {
        LoginUser user = UserContext.getRequired();
        List<String> actions = workflowEngine.allowedActions(t, user).stream()
                .map(TicketAction::name).toList();
        boolean canEdit = workflowEngine.canEdit(t, user);
        return TicketResponse.from(t, slaService.evaluate(t), actions, canEdit);
    }

    private Ticket requireTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("工单不存在：id=" + id));
    }

    private TicketEvent buildEvent(Ticket ticket, TicketEventType type, TicketStatus from,
                                   TicketStatus to, LoginUser operator, String note) {
        TicketEvent e = new TicketEvent();
        e.setTicketId(ticket.getId());
        e.setAction(type.name());
        e.setFromStatus(from == null ? null : from.name());
        e.setToStatus(to == null ? null : to.name());
        e.setOperatorId(operator.id());
        e.setOperatorName(operator.name());
        e.setNote(note);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value, String fieldName) {
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + "不合法：" + value);
        }
    }

    /** 工单编号：SF + 日期 + 4 位随机字符，撞号时重试（唯一索引兜底） */
    private String generateTicketNo() {
        for (int i = 0; i < 5; i++) {
            StringBuilder suffix = new StringBuilder(4);
            for (int j = 0; j < 4; j++) {
                suffix.append(NO_CHARS.charAt(RANDOM.nextInt(NO_CHARS.length())));
            }
            String no = "SF" + LocalDateTime.now().format(NO_DATE) + "-" + suffix;
            if (!ticketRepository.existsByTicketNo(no)) {
                return no;
            }
        }
        throw new IllegalStateException("工单编号生成失败，请重试");
    }
}

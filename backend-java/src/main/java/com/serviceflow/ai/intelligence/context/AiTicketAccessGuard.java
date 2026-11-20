package com.serviceflow.ai.intelligence.context;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * AI 模块的逐单访问控制。
 * 现有工单读接口对任意登录用户开放；AI 会话与分析涉及对话内容，必须更严格：
 * ADMIN 全量；USER 仅本人工单；AGENT 仅分配给本人的工单。
 */
@Component
public class AiTicketAccessGuard {

    private final TicketRepository ticketRepository;

    public AiTicketAccessGuard(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    public Ticket requireAccessible(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("工单不存在：id=" + ticketId));
        LoginUser user = currentUser();
        if (!canAccess(ticket, user)) {
            throw new ForbiddenException("无权访问该工单的 AI 能力");
        }
        return ticket;
    }

    public boolean canAccess(Ticket ticket, LoginUser user) {
        if (user.role() == UserRole.ADMIN) {
            return true;
        }
        if (Objects.equals(ticket.getCreatorId(), user.id())
                || Objects.equals(ticket.getAssigneeId(), user.id())) {
            return true;
        }
        // 历史数据可能只有显示名，按名称回退，与工作流引擎保持一致
        if (user.role() == UserRole.USER) {
            return Objects.equals(ticket.getCreator(), user.name());
        }
        return Objects.equals(ticket.getCreator(), user.name())
                || Objects.equals(ticket.getAssignee(), user.name());
    }

    public void requireAdmin() {
        if (currentUser().role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可执行该操作");
        }
    }

    public LoginUser currentUser() {
        return com.serviceflow.ai.auth.context.UserContext.getRequired();
    }

    public boolean isAdmin() {
        return currentUser().role() == UserRole.ADMIN;
    }
}

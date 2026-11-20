package com.serviceflow.ai.ticket.workflow;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.enums.TicketAction;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流引擎：只做纯规则判断，不碰数据库。
 * Controller/Service 执行动作前必须经过这里，前端拿到的 allowedActions 也由这里生成。
 */
@Component
public class TicketWorkflowEngine {

    /** 动作对应的目标状态；返回 null 表示当前状态不允许该动作 */
    public TicketStatus targetStatus(TicketStatus from, TicketAction action) {
        if (from == null) {
            return null;
        }
        return switch (action) {
            case ASSIGN -> from == TicketStatus.PENDING ? TicketStatus.ASSIGNED : null;
            case TRANSFER -> switch (from) {
                case ASSIGNED, ACCEPTED, PROCESSING -> TicketStatus.ASSIGNED;
                default -> null;
            };
            case ACCEPT -> from == TicketStatus.ASSIGNED ? TicketStatus.ACCEPTED : null;
            case START -> from == TicketStatus.ACCEPTED ? TicketStatus.PROCESSING : null;
            case RESOLVE -> from == TicketStatus.PROCESSING ? TicketStatus.RESOLVED : null;
            case CLOSE -> from == TicketStatus.RESOLVED ? TicketStatus.CLOSED : null;
            case CANCEL -> switch (from) {
                case PENDING, ASSIGNED, ACCEPTED, PROCESSING -> TicketStatus.CANCELLED;
                default -> null;
            };
        };
    }

    public boolean can(Ticket ticket, TicketAction action, LoginUser user) {
        if (user == null) {
            return false;
        }
        TicketStatus from = TicketStatus.parse(ticket.getStatus());
        if (targetStatus(from, action) == null) {
            return false;
        }
        if (user.role() == UserRole.ADMIN) {
            return true;
        }
        return switch (action) {
            case ASSIGN, CLOSE, CANCEL -> isCreator(ticket, user);
            case TRANSFER, ACCEPT, START, RESOLVE -> isAssignee(ticket, user);
        };
    }

    public List<TicketAction> allowedActions(Ticket ticket, LoginUser user) {
        List<TicketAction> actions = new ArrayList<>();
        for (TicketAction action : TicketAction.values()) {
            if (can(ticket, action, user)) {
                actions.add(action);
            }
        }
        return actions;
    }

    public void assertAllowed(Ticket ticket, TicketAction action, LoginUser user) {
        if (!can(ticket, action, user)) {
            throw new ForbiddenException("当前用户无权对工单执行该操作，或当前状态不允许");
        }
    }

    /** 内容编辑权限：管理员、创建人、当前处理人；终态不可编辑 */
    public boolean canEdit(Ticket ticket, LoginUser user) {
        if (user == null || TicketStatus.parse(ticket.getStatus()).terminal()) {
            return false;
        }
        return user.role() == UserRole.ADMIN || isCreator(ticket, user) || isAssignee(ticket, user);
    }

    /** 优先按用户 id 判断；历史数据 id 为空时回退显示名 */
    public boolean isAssignee(Ticket ticket, LoginUser user) {
        if (ticket.getAssigneeId() != null) {
            return ticket.getAssigneeId().equals(user.id());
        }
        return ticket.getAssignee() != null && ticket.getAssignee().equals(user.name());
    }

    public boolean isCreator(Ticket ticket, LoginUser user) {
        if (ticket.getCreatorId() != null) {
            return ticket.getCreatorId().equals(user.id());
        }
        return ticket.getCreator() != null && ticket.getCreator().equals(user.name());
    }
}

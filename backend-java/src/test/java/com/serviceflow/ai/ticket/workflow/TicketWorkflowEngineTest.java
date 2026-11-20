package com.serviceflow.ai.ticket.workflow;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.enums.TicketAction;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工作流规则单元测试：不依赖数据库。 */
class TicketWorkflowEngineTest {

    private final TicketWorkflowEngine engine = new TicketWorkflowEngine();

    private final LoginUser admin = new LoginUser(1L, "admin", "系统管理员", UserRole.ADMIN);
    private final LoginUser creator = new LoginUser(2L, "bob", "小明", UserRole.USER);
    private final LoginUser assignee = new LoginUser(3L, "alice", "小红", UserRole.USER);
    private final LoginUser stranger = new LoginUser(4L, "tom", "汤姆", UserRole.USER);

    private Ticket ticket(TicketStatus status, Long creatorId, Long assigneeId) {
        Ticket t = new Ticket();
        t.setId(100L);
        t.setStatus(status.name());
        t.setCreatorId(creatorId);
        t.setAssigneeId(assigneeId);
        t.setCreator("小明");
        t.setAssignee("小红");
        return t;
    }

    @Test
    void targetStatusFollowsMainFlow() {
        assertEquals(TicketStatus.ASSIGNED, engine.targetStatus(TicketStatus.PENDING, TicketAction.ASSIGN));
        assertEquals(TicketStatus.ACCEPTED, engine.targetStatus(TicketStatus.ASSIGNED, TicketAction.ACCEPT));
        assertEquals(TicketStatus.PROCESSING, engine.targetStatus(TicketStatus.ACCEPTED, TicketAction.START));
        assertEquals(TicketStatus.RESOLVED, engine.targetStatus(TicketStatus.PROCESSING, TicketAction.RESOLVE));
        assertEquals(TicketStatus.CLOSED, engine.targetStatus(TicketStatus.RESOLVED, TicketAction.CLOSE));
        assertEquals(TicketStatus.CANCELLED, engine.targetStatus(TicketStatus.PROCESSING, TicketAction.CANCEL));
    }

    @Test
    void illegalJumpsAreRejected() {
        assertNull(engine.targetStatus(TicketStatus.PENDING, TicketAction.START));
        assertNull(engine.targetStatus(TicketStatus.PENDING, TicketAction.RESOLVE));
        assertNull(engine.targetStatus(TicketStatus.ASSIGNED, TicketAction.RESOLVE));
        assertNull(engine.targetStatus(TicketStatus.RESOLVED, TicketAction.CANCEL));
        assertNull(engine.targetStatus(TicketStatus.CLOSED, TicketAction.CLOSE));
        assertNull(engine.targetStatus(TicketStatus.CANCELLED, TicketAction.ACCEPT));
    }

    @Test
    void pendingCreatorCanAssignAndCancelButNotAccept() {
        Ticket t = ticket(TicketStatus.PENDING, creator.id(), null);
        List<TicketAction> actions = engine.allowedActions(t, creator);
        assertTrue(actions.contains(TicketAction.ASSIGN));
        assertTrue(actions.contains(TicketAction.CANCEL));
        assertFalse(actions.contains(TicketAction.ACCEPT));
        assertFalse(engine.can(t, TicketAction.CANCEL, stranger));
    }

    @Test
    void assigneeCanAcceptStartResolveButNotClose() {
        Ticket t = ticket(TicketStatus.ASSIGNED, creator.id(), assignee.id());
        assertTrue(engine.can(t, TicketAction.ACCEPT, assignee));
        assertFalse(engine.can(t, TicketAction.ACCEPT, stranger));

        t.setStatus(TicketStatus.PROCESSING.name());
        assertTrue(engine.can(t, TicketAction.RESOLVE, assignee));
        assertFalse(engine.can(t, TicketAction.RESOLVE, creator));
        assertFalse(engine.can(t, TicketAction.CLOSE, assignee));
    }

    @Test
    void onlyCreatorOrAdminCanClose() {
        Ticket t = ticket(TicketStatus.RESOLVED, creator.id(), assignee.id());
        assertTrue(engine.can(t, TicketAction.CLOSE, creator));
        assertTrue(engine.can(t, TicketAction.CLOSE, admin));
        assertFalse(engine.can(t, TicketAction.CLOSE, assignee));
        assertFalse(engine.can(t, TicketAction.CLOSE, stranger));
    }

    @Test
    void adminCanDoEverythingStateAllows() {
        Ticket t = ticket(TicketStatus.PENDING, creator.id(), null);
        assertTrue(engine.can(t, TicketAction.ASSIGN, admin));
        assertTrue(engine.can(t, TicketAction.CANCEL, admin));
        assertFalse(engine.can(t, TicketAction.ACCEPT, admin));
    }

    @Test
    void terminalTicketsHaveNoActionsAndCannotBeEdited() {
        Ticket closed = ticket(TicketStatus.CLOSED, creator.id(), assignee.id());
        assertTrue(engine.allowedActions(closed, admin).isEmpty());
        assertFalse(engine.canEdit(closed, admin));

        Ticket cancelled = ticket(TicketStatus.CANCELLED, creator.id(), assignee.id());
        assertTrue(engine.allowedActions(cancelled, admin).isEmpty());
    }
}

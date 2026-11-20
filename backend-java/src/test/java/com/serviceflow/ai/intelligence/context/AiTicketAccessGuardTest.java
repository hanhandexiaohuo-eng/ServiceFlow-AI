package com.serviceflow.ai.intelligence.context;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI 逐单权限：管理员全量，普通用户本人创建，处理人员本人处理。 */
class AiTicketAccessGuardTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final AiTicketAccessGuard guard = new AiTicketAccessGuard(ticketRepository);

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private Ticket ticket(long id, Long creatorId, String creator, Long assigneeId, String assignee) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setTicketNo("SF20261116-TEST");
        t.setTitle("测试工单");
        t.setCreator(creator);
        t.setCreatorId(creatorId);
        t.setAssignee(assignee);
        t.setAssigneeId(assigneeId);
        return t;
    }

    @Test
    void adminCanAccessAnyTicket() {
        Ticket t = ticket(1L, 9L, "张三", null, null);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(t));
        UserContext.set(new LoginUser(1L, "admin", "管理员", UserRole.ADMIN));

        assertSame(t, guard.requireAccessible(1L));
    }

    @Test
    void userCanAccessOwnTicket() {
        Ticket t = ticket(1L, 5L, "李四", null, null);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(t));
        UserContext.set(new LoginUser(5L, "lisi", "李四", UserRole.USER));

        assertSame(t, guard.requireAccessible(1L));
    }

    @Test
    void userCannotAccessOthersTicket() {
        Ticket t = ticket(1L, 9L, "张三", null, null);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(t));
        UserContext.set(new LoginUser(5L, "lisi", "李四", UserRole.USER));

        assertThrows(ForbiddenException.class, () -> guard.requireAccessible(1L));
    }

    @Test
    void agentCanAccessAssignedTicket() {
        Ticket t = ticket(1L, 9L, "张三", 6L, "王五");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(t));
        UserContext.set(new LoginUser(6L, "wangwu", "王五", UserRole.AGENT));

        assertSame(t, guard.requireAccessible(1L));
    }

    @Test
    void agentCannotAccessUnrelatedTicket() {
        Ticket t = ticket(1L, 9L, "张三", 6L, "王五");
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(t));
        UserContext.set(new LoginUser(7L, "zhaoliu", "赵六", UserRole.AGENT));

        assertThrows(ForbiddenException.class, () -> guard.requireAccessible(1L));
    }

    @Test
    void missingTicketReturns404() {
        when(ticketRepository.findById(404L)).thenReturn(Optional.empty());
        UserContext.set(new LoginUser(1L, "admin", "管理员", UserRole.ADMIN));

        NotFoundException e = assertThrows(NotFoundException.class, () -> guard.requireAccessible(404L));
        assertEquals("工单不存在：id=404", e.getMessage());
    }

    @Test
    void requireAdminRejectsNonAdmin() {
        UserContext.set(new LoginUser(5L, "lisi", "李四", UserRole.USER));

        assertThrows(ForbiddenException.class, guard::requireAdmin);
    }
}

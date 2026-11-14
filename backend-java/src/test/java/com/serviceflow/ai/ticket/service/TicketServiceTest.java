package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.serviceflow.ai.ticket.entity.Ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单业务测试（纯单元，mock Repository）：
 * 重点验证模块 1 的两个集成点——creator 来自登录上下文、删除仅管理员。
 */
class TicketServiceTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketService ticketService = new TicketService(ticketRepository);

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void loginAs(String name, UserRole role) {
        UserContext.set(new LoginUser(1L, "tester", name, role));
    }

    @Test
    void createUsesCurrentUserNameAsCreator() {
        loginAs("张三", UserRole.USER);
        when(ticketRepository.insert(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketResponse resp = ticketService.create(
                new TicketCreateRequest("打印机故障", "描述", "INCIDENT", null, null));

        // 创建人来自登录上下文显示名，不是请求参数
        assertEquals("张三", resp.creator());
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).insert(captor.capture());
        assertEquals("张三", captor.getValue().getCreator());
        assertEquals("OPEN", captor.getValue().getStatus());
    }

    @Test
    void deleteAsNormalUserReturns403() {
        loginAs("张三", UserRole.USER);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> ticketService.delete(1L));
        assertEquals("仅管理员可删除工单", e.getMessage());
        // 权限不足时不能执行到删除 SQL
        verify(ticketRepository, never()).deleteById(any());
    }

    @Test
    void deleteAsAdminSucceeds() {
        loginAs("系统管理员", UserRole.ADMIN);
        when(ticketRepository.deleteById(1L)).thenReturn(1);

        ticketService.delete(1L);

        verify(ticketRepository).deleteById(1L);
    }

    @Test
    void createWithoutLoginContextFails() {
        // 上下文缺失说明拦截器漏配，属于编程错误，必须暴露而不是静默放行
        assertThrows(IllegalStateException.class, () -> ticketService.create(
                new TicketCreateRequest("标题", null, "INCIDENT", null, null)));
    }
}

package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.service.UserQueryService;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketEventRepository;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.workflow.TicketWorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单业务测试（纯单元，mock Repository）：
 * 覆盖创建人来源、初始状态、删除权限、旧 PUT 状态入口拒绝。
 */
class TicketServiceTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketEventRepository eventRepository = mock(TicketEventRepository.class);
    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final TicketService ticketService = new TicketService(
            ticketRepository, eventRepository, new TicketSlaService(),
            new TicketWorkflowEngine(), userQueryService);

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

        assertEquals("张三", resp.creator());
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).insert(captor.capture());
        assertEquals("张三", captor.getValue().getCreator());
        // 未指定处理人 → PENDING；SLA 截止时间由后端计算
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(24, resp.sla().hours());
        verify(eventRepository).insert(any());
    }

    @Test
    void updateRejectsStatusChange() {
        loginAs("张三", UserRole.USER);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ticketService.update(1L, new TicketUpdateRequest(
                        "标题", "描述", "INCIDENT", "HIGH", "PROCESSING", null)));
        assertEquals("状态变更请使用工作流接口（接单/开始处理/解决/关闭/取消）", e.getMessage());
    }

    @Test
    void updateRejectsAssigneeChange() {
        loginAs("张三", UserRole.USER);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ticketService.update(1L, new TicketUpdateRequest(
                        "标题", "描述", "INCIDENT", "HIGH", null, "李四")));
        assertEquals("处理人变更请使用分配或转交接口", e.getMessage());
    }

    @Test
    void deleteAsNormalUserReturns403() {
        loginAs("张三", UserRole.USER);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> ticketService.delete(1L));
        assertEquals("仅管理员可删除工单", e.getMessage());
        verify(ticketRepository, never()).deleteById(any());
    }

    @Test
    void deleteAsAdminSucceeds() {
        loginAs("系统管理员", UserRole.ADMIN);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(new Ticket()));
        when(ticketRepository.deleteById(1L)).thenReturn(1);

        ticketService.delete(1L);

        verify(eventRepository).deleteByTicketId(1L);
        verify(ticketRepository).deleteById(1L);
    }

    @Test
    void createWithoutLoginContextFails() {
        // 上下文缺失说明拦截器漏配，属于编程错误，必须暴露而不是静默放行
        assertThrows(IllegalStateException.class, () -> ticketService.create(
                new TicketCreateRequest("标题", null, "INCIDENT", null, null)));
    }
}

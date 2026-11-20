package com.serviceflow.ai.ticket.controller;

import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.GlobalExceptionHandler;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.service.TicketService;
import com.serviceflow.ai.ticket.service.TicketWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller 层测试：Mock 掉 Service，不连真实数据库。
 * 验证 HTTP 状态码、统一响应结构、参数校验、新工作流入口。
 */
class TicketControllerTest {

    private TicketService ticketService;
    private TicketWorkflowService workflowService;
    private MockMvc mockMvc;

    private static final LocalDateTime NOW = LocalDateTime.now();

    private static final TicketResponse SAMPLE = new TicketResponse(
            1L, "SF20261114-AB12", "打印机无法连接", "三楼打印机脱机",
            "INCIDENT", "HIGH", "PENDING", "张三", 1L, "李四", 2L,
            NOW, NOW, null, null, null, null,
            new TicketSlaResponse("HIGH 优先级 8 小时响应", 8, NOW.plusHours(8),
                    "ON_TRACK", 480, 0, null),
            List.of("ASSIGN", "CANCEL"), true);

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        workflowService = mock(TicketWorkflowService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TicketController(ticketService, workflowService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturns201WithTicketNo() throws Exception {
        when(ticketService.create(any(TicketCreateRequest.class))).thenReturn(SAMPLE);

        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"打印机无法连接","description":"三楼打印机脱机",
                                 "type":"INCIDENT","priority":"HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.ticketNo").value("SF20261114-AB12"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.sla.state").value("ON_TRACK"))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("ASSIGN"));
    }

    @Test
    void createWithBlankTitleReturns400() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"","type":"INCIDENT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("标题不能为空"));
    }

    @Test
    void pageReturnsListAndTotal() throws Exception {
        when(ticketService.page(eq("PENDING"), eq("HIGH"), eq(1), eq(10)))
                .thenReturn(new PageResponse<>(List.of(SAMPLE), 1, 1, 10));

        mockMvc.perform(get("/api/tickets")
                        .param("status", "PENDING")
                        .param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].ticketNo").value("SF20261114-AB12"));

        verify(ticketService).page("PENDING", "HIGH", 1, 10);
    }

    @Test
    void pageWithIllegalStatusReturns400() throws Exception {
        when(ticketService.page(eq("BAD"), any(), eq(1), eq(10)))
                .thenThrow(new IllegalArgumentException("状态不合法：BAD"));

        mockMvc.perform(get("/api/tickets").param("status", "BAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("状态不合法：BAD"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(ticketService.getById(1L)).thenReturn(SAMPLE);

        mockMvc.perform(get("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("打印机无法连接"));
    }

    @Test
    void getByIdNotFoundReturns404() throws Exception {
        when(ticketService.getById(999L)).thenThrow(new NotFoundException("工单不存在：id=999"));

        mockMvc.perform(get("/api/tickets/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("工单不存在：id=999"));
    }

    @Test
    void updateReturns200() throws Exception {
        when(ticketService.update(eq(1L), any(TicketUpdateRequest.class))).thenReturn(SAMPLE);

        mockMvc.perform(put("/api/tickets/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"打印机无法连接","description":"已重启",
                                 "type":"INCIDENT","priority":"HIGH"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void acceptCallsWorkflowService() throws Exception {
        when(workflowService.accept(1L)).thenReturn(new Ticket());
        when(ticketService.toResponse(any(Ticket.class))).thenReturn(SAMPLE);

        mockMvc.perform(post("/api/tickets/1/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(workflowService).accept(1L);
    }

    @Test
    void assignRequiresAssignee() throws Exception {
        mockMvc.perform(post("/api/tickets/1/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignee":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void deleteReturns200() throws Exception {
        mockMvc.perform(delete("/api/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(ticketService).delete(1L);
    }

    @Test
    void deleteNotFoundReturns404() throws Exception {
        doThrow(new NotFoundException("工单不存在：id=999")).when(ticketService).delete(999L);

        mockMvc.perform(delete("/api/tickets/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}

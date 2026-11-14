package com.serviceflow.ai.ticket.controller;

import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.GlobalExceptionHandler;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.service.TicketService;
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
 * Controller 层测试：Mock 掉 TicketService，不连真实数据库。
 * 验证点：HTTP 状态码、统一响应结构、参数校验、筛选参数传递。
 */
class TicketControllerTest {

    private TicketService ticketService;
    private MockMvc mockMvc;

    private static final TicketResponse SAMPLE = new TicketResponse(
            1L, "SF20261114-AB12", "打印机无法连接", "三楼打印机脱机",
            "INCIDENT", "HIGH", "OPEN", "张三", "李四",
            LocalDateTime.now(), LocalDateTime.now());

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TicketController(ticketService))
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
                                 "type":"INCIDENT","priority":"HIGH","creator":"张三"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.ticketNo").value("SF20261114-AB12"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    void createWithBlankTitleReturns400() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"","type":"INCIDENT","creator":"张三"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("标题不能为空"));
    }

    @Test
    void pageReturnsListAndTotal() throws Exception {
        when(ticketService.page(eq("OPEN"), eq("HIGH"), eq(1), eq(10)))
                .thenReturn(new PageResponse<>(List.of(SAMPLE), 1, 1, 10));

        mockMvc.perform(get("/api/tickets")
                        .param("status", "OPEN")
                        .param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].ticketNo").value("SF20261114-AB12"));

        verify(ticketService).page("OPEN", "HIGH", 1, 10);
    }

    @Test
    void pageWithIllegalStatusReturns400() throws Exception {
        // 非法枚举值由 Service 抛 IllegalArgumentException，全局异常处理器转 400
        when(ticketService.page(eq("BAD"), any(), eq(1), eq(10)))
                .thenThrow(new IllegalArgumentException("状态不合法：BAD"));

        mockMvc.perform(get("/api/tickets").param("status", "BAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("状态不合法：BAD"));
    }

    // 注：@RequestParam 的 @Min/@Max 校验（如 page=0 → 400）在真实运行环境由
    // Spring 6.1 方法校验生效，standalone MockMvc 不触发该机制，此处不做单测，
    // 留待真实启动后联调验证。

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
                                 "type":"INCIDENT","priority":"HIGH","status":"IN_PROGRESS","assignee":"李四"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
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

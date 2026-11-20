package com.serviceflow.ai.analytics.controller;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import com.serviceflow.ai.analytics.service.AnalyticsSecurity;
import com.serviceflow.ai.analytics.service.SlaAnalyticsService;
import com.serviceflow.ai.analytics.service.TicketAnalyticsService;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 运营分析接口：统一响应、日期格式与区间校验、USER 角色 403。 */
class AnalyticsControllerTest {

    private final TicketAnalyticsService ticketAnalyticsService = mock(TicketAnalyticsService.class);
    private final SlaAnalyticsService slaAnalyticsService = mock(SlaAnalyticsService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsController(ticketAnalyticsService, slaAnalyticsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void overviewReturns200WithApiResponseAndDefaultRange() throws Exception {
        when(ticketAnalyticsService.overview(any())).thenReturn(Map.of(
                "total", 21L, "resolved", 6L, "closed", 5L, "processing", 4L, "cancelled", 1L));

        mockMvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.processing").value(4));

        ArgumentCaptor<AnalyticsQuery> captor = ArgumentCaptor.forClass(AnalyticsQuery.class);
        verify(ticketAnalyticsService).overview(captor.capture());
        LocalDate today = LocalDate.now();
        assertEquals(today, captor.getValue().endDate());
        assertEquals(today.minusDays(29), captor.getValue().startDate());
    }

    @Test
    void illegalDateFormatReturns400() throws Exception {
        mockMvc.perform(get("/api/analytics/overview").param("startDate", "2026-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("日期格式应为 yyyy-MM-dd"));
    }

    @Test
    void startDateAfterEndDateReturns400() throws Exception {
        mockMvc.perform(get("/api/analytics/sla/overview")
                        .param("startDate", "2026-11-10")
                        .param("endDate", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
    }

    @Test
    void userRoleIsForbiddenByRealAnalyticsSecurity() throws Exception {
        TicketAnalyticsService realService = new TicketAnalyticsService(
                mock(AnalyticsRepository.class), new AnalyticsSecurity());
        MockMvc forbiddenMvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsController(realService, slaAnalyticsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(contextStub(UserRole.USER))
                .build();

        forbiddenMvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("仅管理员或处理人员可查看运营分析"));
    }

    /** 模拟 AuthInterceptor：写入当前登录用户并在请求结束后清理。 */
    private static HandlerInterceptor contextStub(UserRole role) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response, Object handler) {
                UserContext.set(new LoginUser(7L, "tester", "测试用户", role));
                return true;
            }

            @Override
            public void afterCompletion(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        Object handler, Exception ex) {
                UserContext.remove();
            }
        };
    }
}

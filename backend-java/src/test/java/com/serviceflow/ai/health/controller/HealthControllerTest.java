package com.serviceflow.ai.health.controller;

import com.serviceflow.ai.health.dto.ComponentCheck;
import com.serviceflow.ai.health.dto.HealthResponse;
import com.serviceflow.ai.health.dto.ReadinessResponse;
import com.serviceflow.ai.health.service.HealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    private HealthService healthService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        healthService = mock(HealthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(healthService)).build();
    }

    @Test
    void healthReturnsUp() throws Exception {
        when(healthService.health()).thenReturn(
                new HealthResponse(HealthService.SERVICE_NAME, "UP", HealthService.VERSION, Instant.now()));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value(HealthService.SERVICE_NAME))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readyReturns503WhenDatabaseDown() throws Exception {
        ReadinessResponse down = new ReadinessResponse(
                HealthService.SERVICE_NAME, "DOWN", HealthService.VERSION, Instant.now(),
                List.of(new ComponentCheck("mysql", "DOWN", "数据库不可用：连接被拒绝")));
        when(healthService.readiness()).thenReturn(down);

        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.checks[0].name").value("mysql"))
                .andExpect(jsonPath("$.checks[0].status").value("DOWN"));
    }
}

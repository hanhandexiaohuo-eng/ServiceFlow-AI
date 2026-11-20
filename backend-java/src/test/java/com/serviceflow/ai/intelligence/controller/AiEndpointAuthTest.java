package com.serviceflow.ai.intelligence.controller;

import com.serviceflow.ai.auth.interceptor.AuthInterceptor;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI 接口同样受 JWT 拦截器保护：未携带 Token 必须 401，而不是直接进入业务层。 */
class AiEndpointAuthTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    @RestController
    static class ProbeController {
        @GetMapping("/api/ai/analyses")
        public String analyses() {
            return "should-not-reach";
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addInterceptors(new AuthInterceptor(jwtService, userRepository, auditLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aiEndpointWithoutTokenReturns401() throws Exception {
        mvc().perform(get("/api/ai/analyses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void knowledgeEndpointWithoutTokenReturns401() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeProbe())
                .addInterceptors(new AuthInterceptor(jwtService, userRepository, auditLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/knowledge"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class KnowledgeProbe {
        @GetMapping("/api/knowledge")
        public String list() {
            return "should-not-reach";
        }
    }
}

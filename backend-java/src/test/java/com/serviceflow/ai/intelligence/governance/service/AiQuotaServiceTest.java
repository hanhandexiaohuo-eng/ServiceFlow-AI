package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.intelligence.client.AiServiceException;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Quota;
import com.serviceflow.ai.intelligence.governance.repository.AiCallLogRepository;
import com.serviceflow.ai.intelligence.governance.repository.AiGovernanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock
    private AiGovernanceRepository governanceRepository;
    @Mock
    private AiCallLogRepository callLogRepository;
    @Mock
    private UserRepository userRepository;

    private AiQuotaService service;

    @BeforeEach
    void setUp() {
        service = new AiQuotaService(governanceRepository, callLogRepository, userRepository);
    }

    @Test
    void 配额用尽时拒绝调用() {
        when(governanceRepository.findQuotas()).thenReturn(List.of(
                new Quota(1L, "USER", "2", "DAY", 3, true)));
        when(callLogRepository.countSince(eq("USER"), eq(2L), any(), any(), eq("CHAT"), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.check(2L, "CHAT"))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("配额");
    }

    @Test
    void 未达上限时放行() {
        when(governanceRepository.findQuotas()).thenReturn(List.of(
                new Quota(1L, "GLOBAL", "", "DAY", 10, true)));
        when(callLogRepository.countSince(eq("GLOBAL"), eq(2L), any(), any(), eq("CHAT"), any())).thenReturn(9L);

        assertThatCode(() -> service.check(2L, "CHAT")).doesNotThrowAnyException();
    }

    @Test
    void 停用的配额不生效() {
        when(governanceRepository.findQuotas()).thenReturn(List.of(
                new Quota(1L, "GLOBAL", "", "DAY", 1, false)));

        assertThatCode(() -> service.check(2L, "CHAT")).doesNotThrowAnyException();
        verify(callLogRepository, never()).countSince(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 部门配额按用户部门统计() {
        when(governanceRepository.findQuotas()).thenReturn(List.of(
                new Quota(1L, "DEPARTMENT", "运维部", "DAY", 5, true)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userWithDepartment("运维部")));
        when(callLogRepository.countSince(eq("DEPARTMENT"), eq(2L), eq("运维部"), any(), eq("CHAT"), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> service.check(2L, "CHAT"))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void 角色配额按用户角色统计() {
        when(governanceRepository.findQuotas()).thenReturn(List.of(
                new Quota(1L, "ROLE", "AGENT", "DAY", 2, true)));
        User agent = new User();
        agent.setId(2L);
        agent.setRole("AGENT");
        when(userRepository.findById(2L)).thenReturn(Optional.of(agent));
        when(callLogRepository.countSince(eq("ROLE"), eq(2L), any(), eq("AGENT"), eq("CHAT"), any()))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.check(2L, "CHAT"))
                .isInstanceOf(AiServiceException.class);
    }

    private User userWithDepartment(String department) {
        User user = new User();
        user.setId(2L);
        user.setDepartment(department);
        return user;
    }
}

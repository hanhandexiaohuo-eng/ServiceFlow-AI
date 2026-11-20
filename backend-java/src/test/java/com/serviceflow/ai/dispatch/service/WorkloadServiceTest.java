package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.entity.AgentProfile;
import com.serviceflow.ai.dispatch.enums.LoadLevel;
import com.serviceflow.ai.dispatch.repository.WorkloadRepository;
import com.serviceflow.ai.dispatch.repository.WorkloadRepository.AgentTicketStats;
import com.serviceflow.ai.dispatch.vo.WorkloadOverviewResponse;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 负载计算：占用口径、负载率、等级边界、可接单判定与汇总。 */
class WorkloadServiceTest {

    private final AgentService agentService = mock(AgentService.class);
    private final WorkloadRepository workloadRepository = mock(WorkloadRepository.class);
    private final DispatchProperties properties = new DispatchProperties();
    private final WorkloadService service = new WorkloadService(
            agentService, workloadRepository, properties, new DispatchSecurity());

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void login(UserRole role) {
        UserContext.set(new LoginUser(1L, "tester", "测试用户", role));
    }

    private User user(long id, String name, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(name.toLowerCase());
        u.setName(name);
        u.setRole(UserRole.AGENT.name());
        u.setStatus(status);
        return u;
    }

    private AgentProfile profile(long id, int max, String availability) {
        AgentProfile p = new AgentProfile();
        p.setUserId(id);
        p.setMaxConcurrent(max);
        p.setAvailability(availability);
        return p;
    }

    private AgentTicketStats stats(long assigned, long accepted, long processing) {
        return new AgentTicketStats(assigned, accepted, processing, 0, 0, null, null, 0, 0);
    }

    private void stubSingleAgent(User agent, AgentProfile profile, AgentTicketStats stats) {
        when(agentService.listAgentUsers()).thenReturn(List.of(agent));
        when(agentService.profileOrDefault(agent)).thenReturn(profile);
        when(workloadRepository.stats(any(), any(), any()))
                .thenReturn(Map.of(agent.getId(), stats));
    }

    @Test
    void normalUserCannotReadWorkload() {
        login(UserRole.USER);
        assertThrows(ForbiddenException.class, () -> service.list());
        verifyNoInteractions(workloadRepository);
    }

    @Test
    void workloadRateAndCompletionStatsComeFromDatabase() {
        login(UserRole.ADMIN);
        User agent = user(10, "张三", 1);
        AgentProfile profile = profile(10, 4, "AVAILABLE");
        AgentTicketStats stats = new AgentTicketStats(1, 1, 0, 2, 3, 45L, 120.0, 4, 3);
        stubSingleAgent(agent, profile, stats);

        List<WorkloadResponse> list = service.list();

        assertEquals(1, list.size());
        WorkloadResponse w = list.get(0);
        assertEquals(2, w.occupied());
        assertEquals(50.0, w.loadRate());
        assertEquals("NORMAL", w.loadLevel());
        assertTrue(w.canAccept());
        assertEquals(2, w.todayCompleted());
        assertEquals(3, w.weekCompleted());
        assertEquals(45L, w.avgHandlingMinutes());
        assertEquals(75.0, w.slaAchievedRate());
        assertEquals(4, w.completedSample());
    }

    @Test
    void fullLoadCannotAccept() {
        login(UserRole.ADMIN);
        User agent = user(10, "张三", 1);
        stubSingleAgent(agent, profile(10, 5, "AVAILABLE"), stats(3, 1, 1));

        WorkloadResponse w = service.list().get(0);

        assertEquals(100.0, w.loadRate());
        assertEquals("FULL", w.loadLevel());
        assertFalse(w.canAccept());
    }

    @Test
    void busyAndDisabledAccountsCannotAcceptRegardlessOfAvailability() {
        login(UserRole.ADMIN);
        User busy = user(10, "张三", 1);
        User disabled = user(11, "李四", 0);
        when(agentService.listAgentUsers()).thenReturn(List.of(busy, disabled));
        when(agentService.profileOrDefault(busy)).thenReturn(profile(10, 5, "BUSY"));
        when(agentService.profileOrDefault(disabled)).thenReturn(profile(11, 5, "AVAILABLE"));
        when(workloadRepository.stats(any(), any(), any()))
                .thenReturn(Map.of(10L, stats(0, 0, 0), 11L, stats(0, 0, 0)));

        List<WorkloadResponse> list = service.list();

        assertEquals("BUSY", list.get(0).effectiveAvailability());
        assertFalse(list.get(0).canAccept());
        // 账号禁用时人员状态被视为 DISABLED，前端显示 AVAILABLE 也不能接单
        assertEquals("DISABLED", list.get(1).effectiveAvailability());
        assertFalse(list.get(1).canAccept());
    }

    @Test
    void noCompletionSampleLeavesPerformanceFieldsNull() {
        login(UserRole.ADMIN);
        User agent = user(10, "张三", 1);
        stubSingleAgent(agent, profile(10, 5, "AVAILABLE"), stats(0, 0, 0));

        WorkloadResponse w = service.list().get(0);

        assertNull(w.avgHandlingMinutes());
        assertNull(w.avgSlaMinutes());
        assertNull(w.slaAchievedRate());
        assertEquals(0, w.completedSample());
        assertEquals(0.0, w.loadRate());
        assertEquals("LOW", w.loadLevel());
        assertTrue(w.canAccept());
    }

    @Test
    void overviewAggregatesAvailabilityAndLoadLevels() {
        login(UserRole.ADMIN);
        User a = user(10, "张三", 1);
        User b = user(11, "李四", 1);
        User c = user(12, "王五", 0);
        when(agentService.listAgentUsers()).thenReturn(List.of(a, b, c));
        when(agentService.profileOrDefault(a)).thenReturn(profile(10, 5, "AVAILABLE"));
        when(agentService.profileOrDefault(b)).thenReturn(profile(11, 5, "BUSY"));
        when(agentService.profileOrDefault(c)).thenReturn(profile(12, 5, "AVAILABLE"));
        when(workloadRepository.stats(any(), any(), any())).thenReturn(Map.of(
                10L, stats(0, 0, 0),
                11L, stats(4, 0, 0),
                12L, stats(5, 0, 0)));
        when(workloadRepository.countPending()).thenReturn(3L);

        WorkloadOverviewResponse overview = service.overview();

        assertEquals(3, overview.totalAgents());
        assertEquals(1, overview.availableAgents());
        assertEquals(1, overview.busyAgents());
        assertEquals(1, overview.disabledAgents());
        assertEquals(1, overview.lowLoad());
        assertEquals(1, overview.highLoad());
        assertEquals(1, overview.fullLoad());
        assertEquals(9, overview.totalOccupied());
        assertEquals(3, overview.unassignedPending());
        assertEquals(60.0, overview.avgLoadRate());
    }

    @Test
    void loadLevelThresholdsAreConfigurable() {
        assertEquals(LoadLevel.LOW, LoadLevel.of(0, 49, 79, 99));
        assertEquals(LoadLevel.LOW, LoadLevel.of(49, 49, 79, 99));
        assertEquals(LoadLevel.NORMAL, LoadLevel.of(50, 49, 79, 99));
        assertEquals(LoadLevel.NORMAL, LoadLevel.of(79, 49, 79, 99));
        assertEquals(LoadLevel.HIGH, LoadLevel.of(80, 49, 79, 99));
        assertEquals(LoadLevel.HIGH, LoadLevel.of(99, 49, 79, 99));
        assertEquals(LoadLevel.FULL, LoadLevel.of(100, 49, 79, 99));
        assertEquals(LoadLevel.FULL, LoadLevel.of(150, 49, 79, 99));
    }
}

package com.serviceflow.ai.dispatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.dto.DispatchAssignRequest;
import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.entity.AgentSkillRelation;
import com.serviceflow.ai.dispatch.entity.DispatchLog;
import com.serviceflow.ai.dispatch.entity.TicketTypeSkillRule;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.repository.DispatchLogRepository;
import com.serviceflow.ai.dispatch.repository.TicketTypeSkillRuleRepository;
import com.serviceflow.ai.dispatch.strategy.PerformanceScoreStrategy;
import com.serviceflow.ai.dispatch.strategy.SkillMatchStrategy;
import com.serviceflow.ai.dispatch.strategy.SlaUrgencyScoreStrategy;
import com.serviceflow.ai.dispatch.strategy.WorkloadScoreStrategy;
import com.serviceflow.ai.dispatch.vo.DispatchAssignResponse;
import com.serviceflow.ai.dispatch.vo.DispatchCandidateResponse;
import com.serviceflow.ai.dispatch.vo.DispatchExcludedResponse;
import com.serviceflow.ai.dispatch.vo.DispatchLogResponse;
import com.serviceflow.ai.dispatch.vo.DispatchRecommendationResponse;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.service.TicketService;
import com.serviceflow.ai.ticket.service.TicketSlaService;
import com.serviceflow.ai.ticket.service.TicketWorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 规则调度：候选过滤、可解释推荐、确认分配必须走工作流并写日志。 */
class DispatchServiceTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TicketWorkflowService workflowService = mock(TicketWorkflowService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkloadService workloadService = mock(WorkloadService.class);
    private final AgentSkillRepository skillRepository = mock(AgentSkillRepository.class);
    private final TicketTypeSkillRuleRepository ruleRepository = mock(TicketTypeSkillRuleRepository.class);
    private final DispatchLogRepository logRepository = mock(DispatchLogRepository.class);
    private final DispatchProperties properties = new DispatchProperties();
    private final DispatchScoringService scoringService = new DispatchScoringService(
            List.of(new SkillMatchStrategy(), new WorkloadScoreStrategy(),
                    new SlaUrgencyScoreStrategy(), new PerformanceScoreStrategy(properties)),
            properties);

    private final DispatchService service = new DispatchService(
            new DispatchSecurity(), ticketRepository, ticketService, workflowService,
            new TicketSlaService(), agentService, workloadService, skillRepository,
            ruleRepository, scoringService, logRepository, properties, new ObjectMapper());

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void login(UserRole role) {
        UserContext.set(new LoginUser(role == UserRole.ADMIN ? 1L : 90L, "tester", "测试用户", role));
    }

    private Ticket ticket(String status) {
        Ticket t = new Ticket();
        t.setId(1L);
        t.setTicketNo("SF20261116-ABCD");
        t.setTitle("报销系统无法登录");
        t.setType("SOFTWARE");
        t.setPriority("URGENT");
        t.setStatus(status);
        t.setCreatedAt(LocalDateTime.now().minusHours(1));
        t.setSlaDueAt(LocalDateTime.now().plusHours(3));
        return t;
    }

    private WorkloadResponse workload(long id, String name, int accountStatus, String availability,
                                      long occupied, int max, boolean canAccept,
                                      long sample, Double slaRate, Long avgHandle, Double avgSla) {
        double rate = max <= 0 ? 0 : occupied * 100.0 / max;
        return new WorkloadResponse(id, name, name.toLowerCase(), "AGENT", accountStatus,
                availability, availability, max, occupied, 0, 0, occupied, rate, "LOW", canAccept,
                0, 0, avgHandle, avgSla, slaRate, sample);
    }

    private AgentSkill skill(long id, String code, String name) {
        AgentSkill s = new AgentSkill();
        s.setId(id);
        s.setSkillCode(code);
        s.setSkillName(name);
        return s;
    }

    private AgentSkillRelation relation(long agentId, long skillId, int level) {
        AgentSkillRelation r = new AgentSkillRelation();
        r.setAgentId(agentId);
        r.setSkillId(skillId);
        r.setProficiencyLevel(level);
        return r;
    }

    private TicketTypeSkillRule rule(long skillId, boolean required) {
        TicketTypeSkillRule r = new TicketTypeSkillRule();
        r.setTicketType("SOFTWARE");
        r.setSkillId(skillId);
        r.setRequired(required ? 1 : 0);
        return r;
    }

    private User agentUser(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name.toLowerCase());
        u.setName(name);
        u.setRole(UserRole.AGENT.name());
        u.setStatus(1);
        return u;
    }

    /** 6 个人员覆盖：正常候选、缺技能、离线、满负载、低熟练度、账号禁用。 */
    private void stubScenario() {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket("PENDING")));
        when(ruleRepository.findByTicketType("SOFTWARE"))
                .thenReturn(List.of(rule(1, true), rule(2, false)));
        when(skillRepository.findAll()).thenReturn(List.of(
                skill(1, "JAVA", "Java 开发"),
                skill(2, "DB", "数据库"),
                skill(3, "NET", "网络运维")));
        when(workloadService.computeAll()).thenReturn(List.of(
                workload(10, "张三", 1, "AVAILABLE", 0, 5, true, 6, 100.0, 60L, 240.0),
                workload(11, "李四", 1, "AVAILABLE", 0, 5, true, 0, null, null, null),
                workload(12, "王五", 1, "OFFLINE", 0, 5, false, 0, null, null, null),
                workload(13, "赵六", 1, "AVAILABLE", 5, 5, true, 0, null, null, null),
                workload(14, "钱七", 1, "AVAILABLE", 1, 5, true, 0, null, null, null),
                workload(15, "孙八", 0, "DISABLED", 0, 5, false, 0, null, null, null)));
        when(skillRepository.findRelationsByAgentIds(any())).thenReturn(List.of(
                relation(10, 1, 3), relation(10, 2, 4),
                relation(12, 1, 4), relation(13, 1, 4),
                relation(14, 1, 1), relation(15, 1, 4)));
        when(logRepository.insert(any())).thenAnswer(inv -> {
            DispatchLog log = inv.getArgument(0);
            log.setId(77L);
            return log;
        });
    }

    private String reasonOf(DispatchRecommendationResponse response, long agentId) {
        return response.excluded().stream()
                .filter(e -> e.agentId() == agentId)
                .map(DispatchExcludedResponse::reasons)
                .map(r -> String.join("；", r))
                .findFirst().orElse("");
    }

    @Test
    void onlyAdminCanRecommend() {
        login(UserRole.AGENT);
        assertThrows(ForbiddenException.class, () -> service.recommend(1L, 5));
        verifyNoInteractions(ticketRepository);
    }

    @Test
    void recommendRejectsNonPendingTicket() {
        login(UserRole.ADMIN);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket("ASSIGNED")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.recommend(1L, 5));

        assertTrue(e.getMessage().contains("PENDING"));
        verify(logRepository, never()).insert(any());
    }

    @Test
    void recommendRejectsMissingTicket() {
        login(UserRole.ADMIN);
        when(ticketRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.recommend(1L, 5));
    }

    @Test
    void recommendFiltersExcludedAgentsAndRanksCandidates() {
        login(UserRole.ADMIN);
        stubScenario();

        DispatchRecommendationResponse resp = service.recommend(1L, 5);

        assertEquals(2, resp.candidates().size());
        DispatchCandidateResponse top = resp.candidates().get(0);
        assertEquals(10L, top.agentId());
        assertEquals(14L, resp.candidates().get(1).agentId());
        assertTrue(top.score() > resp.candidates().get(1).score());
        assertEquals(0.4, top.breakdown().skillWeight());
        assertEquals(93.0, top.breakdown().skillScore());
        assertTrue(top.matchedSkills().contains("Java 开发"));
        assertTrue(top.matchedSkills().contains("数据库"));
        assertTrue(top.missingOptionalSkills().isEmpty());
        // 样本充足的人员不应出现中性绩效警告
        assertTrue(top.warnings().isEmpty());

        assertEquals(4, resp.excluded().size());
        assertTrue(reasonOf(resp, 11).contains("缺少必需技能：Java 开发"));
        assertTrue(reasonOf(resp, 12).contains("OFFLINE"));
        assertTrue(reasonOf(resp, 13).contains("已达最大并发 5/5"));
        assertTrue(reasonOf(resp, 15).contains("账号已禁用"));

        ArgumentCaptor<DispatchLog> captor = ArgumentCaptor.forClass(DispatchLog.class);
        verify(logRepository).insert(captor.capture());
        DispatchLog log = captor.getValue();
        assertEquals("RECOMMEND", log.getAction());
        assertNull(log.getAgentId());
        assertEquals(2, log.getCandidateCount());
        assertEquals(top.score(), log.getScore());
        assertNotNull(log.getDetail());
        assertEquals("测试用户", log.getOperatorName());
    }

    @Test
    void recommendHonoursLimit() {
        login(UserRole.ADMIN);
        stubScenario();

        DispatchRecommendationResponse resp = service.recommend(1L, 1);

        assertEquals(1, resp.candidates().size());
        assertEquals(10L, resp.candidates().get(0).agentId());
    }

    @Test
    void assignGoesThroughWorkflowAndWritesLog() {
        login(UserRole.ADMIN);
        stubScenario();
        when(agentService.requireAgent(10L)).thenReturn(agentUser(10, "张三"));
        Ticket assigned = ticket("ASSIGNED");
        assigned.setAssignee("张三");
        assigned.setAssigneeId(10L);
        when(workflowService.assign(1L, "张三")).thenReturn(assigned);
        when(ticketService.toResponse(assigned)).thenReturn(TicketResponse.from(assigned,
                new TicketSlaResponse("紧急", 4, LocalDateTime.now().plusHours(2),
                        "ON_TRACK", 120, 10, null),
                List.of(), false));

        DispatchAssignResponse resp = service.assign(
                new DispatchAssignRequest(1L, 10L, "按推荐分配"));

        assertEquals(10L, resp.chosen().agentId());
        assertEquals("ASSIGNED", resp.ticket().status());
        assertEquals("张三", resp.ticket().assignee());
        assertEquals(77L, resp.logId());
        verify(workflowService).assign(1L, "张三");

        ArgumentCaptor<DispatchLog> captor = ArgumentCaptor.forClass(DispatchLog.class);
        verify(logRepository, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        DispatchLog assignLog = captor.getAllValues().stream()
                .filter(l -> "ASSIGN".equals(l.getAction())).findFirst().orElseThrow();
        assertEquals(10L, assignLog.getAgentId());
        assertEquals("张三", assignLog.getAgentName());
        // 日志记录的是被选中人员的分数，而不是榜首分数
        assertEquals(resp.chosen().score(), assignLog.getScore());
        assertEquals("按推荐分配", assignLog.getNote());
    }

    @Test
    void assignRejectsExcludedAgentWithoutTouchingWorkflow() {
        login(UserRole.ADMIN);
        stubScenario();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.assign(new DispatchAssignRequest(1L, 13L, null)));

        assertTrue(e.getMessage().contains("已达最大并发"));
        verify(workflowService, never()).assign(anyLong(), anyString());
        verify(logRepository, never()).insert(any());
    }

    @Test
    void historyIsPaginatedAndAdminOnly() {
        login(UserRole.ADMIN);
        DispatchLog log = new DispatchLog();
        log.setId(5L);
        log.setTicketId(1L);
        log.setTicketNo("SF20261116-ABCD");
        log.setAction("RECOMMEND");
        log.setCreatedAt(LocalDateTime.now());
        when(logRepository.findPage(null, null, 1, 10)).thenReturn(List.of(log));
        when(logRepository.count(null, null)).thenReturn(1L);

        PageResponse<DispatchLogResponse> page = service.history(null, null, 1, 10);

        assertEquals(1, page.total());
        assertEquals(1, page.list().size());
        assertEquals("RECOMMEND", page.list().get(0).action());

        UserContext.set(new LoginUser(90L, "agent", "处理人员", UserRole.AGENT));
        assertThrows(ForbiddenException.class, () -> service.history(null, null, 1, 10));
    }
}

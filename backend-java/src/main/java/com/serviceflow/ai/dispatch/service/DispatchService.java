package com.serviceflow.ai.dispatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.dto.DispatchAssignRequest;
import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.entity.AgentSkillRelation;
import com.serviceflow.ai.dispatch.entity.DispatchLog;
import com.serviceflow.ai.dispatch.entity.TicketTypeSkillRule;
import com.serviceflow.ai.dispatch.enums.AgentAvailability;
import com.serviceflow.ai.dispatch.enums.DispatchAction;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.repository.DispatchLogRepository;
import com.serviceflow.ai.dispatch.repository.TicketTypeSkillRuleRepository;
import com.serviceflow.ai.dispatch.strategy.ScoreInput;
import com.serviceflow.ai.dispatch.vo.DispatchAssignResponse;
import com.serviceflow.ai.dispatch.vo.DispatchCandidateResponse;
import com.serviceflow.ai.dispatch.vo.DispatchExcludedResponse;
import com.serviceflow.ai.dispatch.vo.DispatchLogResponse;
import com.serviceflow.ai.dispatch.vo.DispatchRecommendationResponse;
import com.serviceflow.ai.dispatch.vo.ScoreBreakdown;
import com.serviceflow.ai.dispatch.vo.SkillRequirementResponse;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.service.TicketService;
import com.serviceflow.ai.ticket.service.TicketSlaService;
import com.serviceflow.ai.ticket.service.TicketWorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 规则调度：候选过滤 → 四维评分 → 可解释推荐。
 * 推荐只读，不改变工单状态；确认分配必须经过 TicketWorkflowService.assign 的状态机与权限校验。
 */
@Service
public class DispatchService {

    private final DispatchSecurity security;
    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final TicketWorkflowService workflowService;
    private final TicketSlaService slaService;
    private final AgentService agentService;
    private final WorkloadService workloadService;
    private final AgentSkillRepository skillRepository;
    private final TicketTypeSkillRuleRepository ruleRepository;
    private final DispatchScoringService scoringService;
    private final DispatchLogRepository logRepository;
    private final DispatchProperties properties;
    private final ObjectMapper objectMapper;

    public DispatchService(DispatchSecurity security,
                           TicketRepository ticketRepository,
                           TicketService ticketService,
                           TicketWorkflowService workflowService,
                           TicketSlaService slaService,
                           AgentService agentService,
                           WorkloadService workloadService,
                           AgentSkillRepository skillRepository,
                           TicketTypeSkillRuleRepository ruleRepository,
                           DispatchScoringService scoringService,
                           DispatchLogRepository logRepository,
                           DispatchProperties properties,
                           ObjectMapper objectMapper) {
        this.security = security;
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.workflowService = workflowService;
        this.slaService = slaService;
        this.agentService = agentService;
        this.workloadService = workloadService;
        this.skillRepository = skillRepository;
        this.ruleRepository = ruleRepository;
        this.scoringService = scoringService;
        this.logRepository = logRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PageResponse<TicketResponse> pendingTickets(int page, int size) {
        security.requireAdmin();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        return ticketService.page(TicketStatus.PENDING.name(), null, safePage, safeSize);
    }

    @Transactional
    public DispatchRecommendationResponse recommend(Long ticketId, Integer limit) {
        security.requireAdmin();
        Ticket ticket = requirePending(ticketId);
        int safeLimit = limit == null ? properties.getTopLimit() : Math.max(1, Math.min(20, limit));
        Evaluation evaluation = evaluate(ticket, safeLimit);
        writeLog(evaluation.response(), DispatchAction.RECOMMEND, null, null, "生成调度推荐", null);
        return evaluation.response();
    }

    /**
     * 人工确认分配：重新执行候选过滤，命中后才调用工作流分配。
     * 推荐结果过期（人员已满/离线/工单已被处理）时返回 400，状态并发冲突由工作流返回 409。
     */
    @Transactional
    public DispatchAssignResponse assign(DispatchAssignRequest req) {
        security.requireAdmin();
        Ticket ticket = requirePending(req.ticketId());
        Evaluation evaluation = evaluate(ticket, Integer.MAX_VALUE);
        DispatchCandidateResponse chosen = evaluation.response().candidates().stream()
                .filter(c -> c.agentId().equals(req.agentId()))
                .findFirst()
                .orElseThrow(() -> {
                    List<String> reasons = evaluation.excludedReasons().get(req.agentId());
                    String detail = reasons == null || reasons.isEmpty()
                            ? "该人员不在当前可分配候选中"
                            : String.join("；", reasons);
                    return new IllegalArgumentException("该人员当前不可分配：" + detail);
                });
        User agent = agentService.requireAgent(req.agentId());
        Ticket assigned = workflowService.assign(ticket.getId(), agent.getName());
        TicketResponse response = ticketService.toResponse(assigned);
        DispatchLog log = writeLog(evaluation.response(), DispatchAction.ASSIGN,
                chosen.agentId(), chosen.name(), req.note(), chosen.score());
        return new DispatchAssignResponse(response, log.getId(), chosen);
    }

    public PageResponse<DispatchLogResponse> history(Long ticketId, Long agentId, int page, int size) {
        security.requireAdmin();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<DispatchLogResponse> list = logRepository.findPage(ticketId, agentId, safePage, safeSize)
                .stream().map(this::toLogResponse).toList();
        return new PageResponse<>(list, logRepository.count(ticketId, agentId), safePage, safeSize);
    }

    private Evaluation evaluate(Ticket ticket, int limit) {
        List<TicketTypeSkillRule> rules = ruleRepository.findByTicketType(ticket.getType());
        Map<Long, AgentSkill> catalog = skillRepository.findAll().stream()
                .collect(Collectors.toMap(AgentSkill::getId, Function.identity(), (a, b) -> a));
        Set<Long> requiredIds = rules.stream().filter(TicketTypeSkillRule::isRequired)
                .map(TicketTypeSkillRule::getSkillId).collect(Collectors.toSet());
        Set<Long> optionalIds = rules.stream().filter(r -> !r.isRequired())
                .map(TicketTypeSkillRule::getSkillId).collect(Collectors.toSet());
        List<SkillRequirementResponse> requirements = rules.stream()
                .filter(r -> catalog.containsKey(r.getSkillId()))
                .map(r -> new SkillRequirementResponse(r.getSkillId(), catalog.get(r.getSkillId()).getSkillCode(),
                        catalog.get(r.getSkillId()).getSkillName(), r.isRequired()))
                .toList();

        TicketSlaResponse sla = slaService.evaluate(ticket);
        List<WorkloadResponse> workloads = workloadService.computeAll();
        List<Long> ids = workloads.stream().map(WorkloadResponse::agentId).toList();
        Map<Long, List<AgentSkillRelation>> relations = skillRepository.findRelationsByAgentIds(ids).stream()
                .collect(Collectors.groupingBy(AgentSkillRelation::getAgentId));

        List<DispatchCandidateResponse> candidates = new ArrayList<>();
        List<DispatchExcludedResponse> excluded = new ArrayList<>();
        Map<Long, List<String>> excludedReasons = new LinkedHashMap<>();

        for (WorkloadResponse workload : workloads) {
            List<AgentSkillRelation> agentRelations =
                    relations.getOrDefault(workload.agentId(), List.of());
            Map<Long, Integer> levels = agentRelations.stream()
                    .collect(Collectors.toMap(AgentSkillRelation::getSkillId,
                            AgentSkillRelation::getProficiencyLevel, (a, b) -> a));
            List<String> reasons = exclusionReasons(workload, levels, requiredIds, catalog);

            if (!reasons.isEmpty()) {
                excludedReasons.put(workload.agentId(), reasons);
                excluded.add(new DispatchExcludedResponse(workload.agentId(), workload.name(), reasons));
                continue;
            }

            ScoreInput input = new ScoreInput(requiredIds, optionalIds, levels, workload, sla, sla.hours());
            ScoreBreakdown breakdown = scoringService.score(input);
            List<String> matched = new ArrayList<>();
            List<String> missingOptional = new ArrayList<>();
            for (TicketTypeSkillRule rule : rules) {
                AgentSkill skill = catalog.get(rule.getSkillId());
                if (skill == null) {
                    continue;
                }
                if (levels.containsKey(rule.getSkillId())) {
                    matched.add(skill.getSkillName());
                } else if (!rule.isRequired()) {
                    missingOptional.add(skill.getSkillName());
                }
            }
            List<String> reasonTexts = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (!requiredIds.isEmpty()) {
                reasonTexts.add("具备全部必需技能");
            }
            if (missingOptional.size() < optionalIds.size() && !optionalIds.isEmpty()) {
                reasonTexts.add("命中部分加分技能");
            }
            reasonTexts.add("负载率 " + workload.loadRate() + "%（" + workload.occupied() + "/"
                    + workload.maxConcurrent() + "）");
            reasonTexts.add(slaReason(sla));
            boolean sampleEnough = workload.completedSample() >= properties.getPerformance().getSampleThreshold();
            if (sampleEnough) {
                reasonTexts.add("历史完成 " + workload.completedSample() + " 单，SLA 达成率 "
                        + (workload.slaAchievedRate() == null ? "-" : workload.slaAchievedRate() + "%"));
            } else {
                warnings.add("历史样本不足（" + workload.completedSample() + "/"
                        + properties.getPerformance().getSampleThreshold() + "），绩效按中性分 "
                        + properties.getPerformance().getNeutralScore());
            }
            if (workload.loadRate() >= 80) {
                warnings.add("负载率 " + workload.loadRate() + "%，接近上限");
            }
            if (!missingOptional.isEmpty()) {
                warnings.add("缺少加分技能：" + String.join("、", missingOptional));
            }

            candidates.add(new DispatchCandidateResponse(
                    workload.agentId(), workload.name(), workload.username(),
                    workload.effectiveAvailability(), workload.loadRate(),
                    workload.occupied(), workload.maxConcurrent(),
                    breakdown.total(), breakdown, matched, missingOptional, reasonTexts, warnings));
        }

        candidates.sort(Comparator.comparingDouble(DispatchCandidateResponse::score).reversed()
                .thenComparingDouble(DispatchCandidateResponse::loadRate)
                .thenComparingLong(DispatchCandidateResponse::agentId));
        List<DispatchCandidateResponse> top = candidates.size() > limit
                ? new ArrayList<>(candidates.subList(0, limit)) : candidates;

        DispatchRecommendationResponse response = new DispatchRecommendationResponse(
                ticket.getId(), ticket.getTicketNo(), ticket.getTitle(), ticket.getType(),
                ticket.getPriority(), ticket.getStatus(), sla.state(), sla.remainingMinutes(),
                urgency(sla), requirements, top, excluded,
                scoringService.normalizedWeights(), LocalDateTime.now());
        return new Evaluation(response, excludedReasons);
    }

    private List<String> exclusionReasons(WorkloadResponse workload, Map<Long, Integer> levels,
                                          Set<Long> requiredIds, Map<Long, AgentSkill> catalog) {
        List<String> reasons = new ArrayList<>();
        boolean accountActive = workload.accountStatus() != null && workload.accountStatus() == 1;
        if (!accountActive) {
            reasons.add("账号已禁用");
        } else if (!AgentAvailability.AVAILABLE.name().equals(workload.effectiveAvailability())) {
            reasons.add("人员状态为 " + workload.effectiveAvailability());
        }
        List<String> missingRequired = requiredIds.stream()
                .filter(id -> !levels.containsKey(id))
                .map(id -> catalog.containsKey(id) ? catalog.get(id).getSkillName() : ("技能#" + id))
                .toList();
        if (!missingRequired.isEmpty()) {
            reasons.add("缺少必需技能：" + String.join("、", missingRequired));
        }
        if (workload.occupied() >= workload.maxConcurrent()) {
            reasons.add("已达最大并发 " + workload.occupied() + "/" + workload.maxConcurrent());
        }
        return reasons;
    }

    private String slaReason(TicketSlaResponse sla) {
        if ("OVERDUE".equals(sla.state())) {
            return "SLA 已超时 " + Math.abs(sla.remainingMinutes()) + " 分钟";
        }
        return "SLA 剩余 " + sla.remainingMinutes() + " 分钟（" + sla.state() + "）";
    }

    private double urgency(TicketSlaResponse sla) {
        return com.serviceflow.ai.dispatch.strategy.SlaUrgencyScoreStrategy
                .urgency(sla, sla.hours(), slaService.warningMinutes());
    }

    private DispatchLog writeLog(DispatchRecommendationResponse response, DispatchAction action,
                                 Long agentId, String agentName, String note, Double scoreOverride) {
        LoginUser operator = security.current();
        DispatchLog log = new DispatchLog();
        log.setTicketId(response.ticketId());
        log.setTicketNo(response.ticketNo());
        log.setAction(action.name());
        log.setOperatorId(operator.id());
        log.setOperatorName(operator.name());
        log.setAgentId(agentId);
        log.setAgentName(agentName);
        log.setScore(scoreOverride != null ? scoreOverride
                : response.candidates().isEmpty() ? null : response.candidates().get(0).score());
        log.setCandidateCount(response.candidates().size());
        log.setNote(note);
        log.setDetail(writeDetail(response, action));
        return logRepository.insert(log);
    }

    private String writeDetail(DispatchRecommendationResponse response, DispatchAction action) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action.name());
        detail.put("weights", response.weights());
        detail.put("slaState", response.slaState());
        detail.put("slaRemainingMinutes", response.slaRemainingMinutes());
        detail.put("requiredSkills", response.requiredSkills());
        detail.put("candidates", response.candidates());
        detail.put("excluded", response.excluded());
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("调度日志序列化失败", e);
        }
    }

    private DispatchLogResponse toLogResponse(DispatchLog log) {
        return new DispatchLogResponse(log.getId(), log.getTicketId(), log.getTicketNo(), log.getAction(),
                log.getOperatorId(), log.getOperatorName(), log.getAgentId(), log.getAgentName(),
                log.getScore(), log.getCandidateCount(), log.getNote(), log.getDetail(), log.getCreatedAt());
    }

    private Ticket requirePending(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("工单不存在：id=" + ticketId));
        if (TicketStatus.parse(ticket.getStatus()) != TicketStatus.PENDING) {
            throw new IllegalArgumentException("仅待分配（PENDING）工单参与初始调度推荐");
        }
        return ticket;
    }

    private record Evaluation(DispatchRecommendationResponse response,
                              Map<Long, List<String>> excludedReasons) {
    }
}

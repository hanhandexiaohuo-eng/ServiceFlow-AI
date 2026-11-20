package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.entity.AgentProfile;
import com.serviceflow.ai.dispatch.enums.AgentAvailability;
import com.serviceflow.ai.dispatch.enums.LoadLevel;
import com.serviceflow.ai.dispatch.repository.WorkloadRepository;
import com.serviceflow.ai.dispatch.repository.WorkloadRepository.AgentTicketStats;
import com.serviceflow.ai.dispatch.vo.WorkloadOverviewResponse;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实时负载：占用、负载率、等级、完成量、平均处理时长与 SLA 达成率。
 * 所有数值来自 tickets 真实记录，不使用随机数或静态假数据。
 */
@Service
public class WorkloadService {

    private final AgentService agentService;
    private final WorkloadRepository workloadRepository;
    private final DispatchProperties properties;
    private final DispatchSecurity security;

    public WorkloadService(AgentService agentService,
                           WorkloadRepository workloadRepository,
                           DispatchProperties properties,
                           DispatchSecurity security) {
        this.agentService = agentService;
        this.workloadRepository = workloadRepository;
        this.properties = properties;
        this.security = security;
    }

    public List<WorkloadResponse> list() {
        security.requireAdminOrAgent();
        return computeAll();
    }

    public WorkloadResponse get(Long agentId) {
        security.requireAdminOrAgent();
        User user = agentService.requireAgent(agentId);
        return compute(List.of(user)).get(0);
    }

    public WorkloadOverviewResponse overview() {
        security.requireAdminOrAgent();
        List<WorkloadResponse> workloads = computeAll();
        int available = 0;
        int busy = 0;
        int offline = 0;
        int disabled = 0;
        int low = 0;
        int normal = 0;
        int high = 0;
        int full = 0;
        long occupied = 0;
        double loadSum = 0;
        for (WorkloadResponse w : workloads) {
            switch (w.effectiveAvailability()) {
                case "AVAILABLE" -> available++;
                case "BUSY" -> busy++;
                case "OFFLINE" -> offline++;
                default -> disabled++;
            }
            switch (w.loadLevel()) {
                case "LOW" -> low++;
                case "NORMAL" -> normal++;
                case "HIGH" -> high++;
                default -> full++;
            }
            occupied += w.occupied();
            loadSum += w.loadRate();
        }
        double avgLoad = workloads.isEmpty() ? 0
                : WorkloadRepository.round1(loadSum / workloads.size());
        return new WorkloadOverviewResponse(
                workloads.size(), available, busy, offline, disabled,
                low, normal, high, full,
                avgLoad, occupied, workloadRepository.countPending(), LocalDateTime.now());
    }

    /** 调度内部使用：不重复做权限校验，由调用方控制。 */
    public List<WorkloadResponse> computeAll() {
        return compute(agentService.listAgentUsers());
    }

    public Map<Long, WorkloadResponse> snapshot() {
        return computeAll().stream()
                .collect(Collectors.toMap(WorkloadResponse::agentId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
    }

    private List<WorkloadResponse> compute(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        List<Long> ids = users.stream().map(User::getId).toList();
        Map<Long, AgentTicketStats> stats = workloadRepository.stats(ids, todayStart, weekStart);

        List<WorkloadResponse> result = new ArrayList<>();
        for (User user : users) {
            AgentProfile profile = agentService.profileOrDefault(user);
            int maxConcurrent = Math.max(1, profile.getMaxConcurrent() == null ? 1 : profile.getMaxConcurrent());
            AgentTicketStats s = stats.getOrDefault(user.getId(), AgentTicketStats.empty());
            long occupied = s.occupied();
            double loadRate = WorkloadRepository.round1(occupied * 100.0 / maxConcurrent);
            String effective = AgentService.effectiveAvailability(user, profile);
            boolean accountActive = user.getStatus() != null && user.getStatus() == 1;
            boolean canAccept = accountActive
                    && AgentAvailability.AVAILABLE.name().equals(effective)
                    && occupied < maxConcurrent;
            Double achievedRate = s.completedSample() > 0
                    ? WorkloadRepository.round1(s.slaAchieved() * 100.0 / s.completedSample())
                    : null;

            result.add(new WorkloadResponse(
                    user.getId(), user.getName(), user.getUsername(), user.getRole(), user.getStatus(),
                    profile.getAvailability(), effective, maxConcurrent,
                    s.assigned(), s.accepted(), s.processing(), occupied,
                    loadRate,
                    LoadLevel.of(loadRate, properties.getLoad().getLowMax(),
                            properties.getLoad().getNormalMax(), properties.getLoad().getHighMax()).name(),
                    canAccept,
                    s.todayCompleted(), s.weekCompleted(), s.avgHandlingMinutes(), s.avgSlaMinutes(),
                    achievedRate, s.completedSample()));
        }
        return result;
    }
}

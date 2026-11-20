package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.strategy.PerformanceScoreStrategy;
import com.serviceflow.ai.dispatch.strategy.ScoreInput;
import com.serviceflow.ai.dispatch.strategy.SkillMatchStrategy;
import com.serviceflow.ai.dispatch.strategy.SlaUrgencyScoreStrategy;
import com.serviceflow.ai.dispatch.strategy.WorkloadScoreStrategy;
import com.serviceflow.ai.dispatch.vo.DispatchWeightsResponse;
import com.serviceflow.ai.dispatch.vo.ScoreBreakdown;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 四维评分：权重归一化、分项公式、边界与中性绩效。 */
class DispatchScoringServiceTest {

    private final DispatchProperties properties = new DispatchProperties();
    private final DispatchScoringService scoring = new DispatchScoringService(
            List.of(new SkillMatchStrategy(), new WorkloadScoreStrategy(),
                    new SlaUrgencyScoreStrategy(), new PerformanceScoreStrategy(properties)),
            properties);

    private WorkloadResponse workload(long occupied, int max, long sample, Double slaRate,
                                      Long avgHandling, Double avgSla) {
        double rate = max <= 0 ? 0 : occupied * 100.0 / max;
        return new WorkloadResponse(1L, "张三", "zhangsan", "AGENT", 1,
                "AVAILABLE", "AVAILABLE", max, occupied, 0, 0, occupied, rate, "LOW", true,
                0, 0, avgHandling, avgSla, slaRate, sample);
    }

    private TicketSlaResponse sla(String state, long remainingMinutes) {
        return new TicketSlaResponse("中优先级", 24,
                LocalDateTime.now().plusMinutes(Math.max(0, remainingMinutes)),
                state, remainingMinutes, 0, null);
    }

    private ScoreInput input(WorkloadResponse workload, TicketSlaResponse sla,
                             Set<Long> required, Set<Long> optional, Map<Long, Integer> levels) {
        return new ScoreInput(required, optional, levels, workload, sla, sla.hours());
    }

    private ScoreInput simpleInput(WorkloadResponse workload, TicketSlaResponse sla) {
        return input(workload, sla, Set.of(1L), Set.of(), Map.of(1L, 3));
    }

    @Test
    void defaultWeightsAreNormalizedAsConfigured() {
        DispatchWeightsResponse weights = scoring.normalizedWeights();
        assertEquals(0.4, weights.skill());
        assertEquals(0.3, weights.workload());
        assertEquals(0.2, weights.sla());
        assertEquals(0.1, weights.performance());
    }

    @Test
    void weightsAreNormalizedWhenSumIsNotOne() {
        DispatchProperties custom = new DispatchProperties();
        custom.getWeights().setSkill(2);
        custom.getWeights().setWorkload(1);
        custom.getWeights().setSla(1);
        custom.getWeights().setPerformance(0);
        DispatchWeightsResponse weights = new DispatchScoringService(
                List.of(new SkillMatchStrategy()), custom).normalizedWeights();
        assertEquals(0.5, weights.skill());
        assertEquals(0.25, weights.workload());
        assertEquals(0.25, weights.sla());
        assertEquals(0.0, weights.performance());
    }

    @Test
    void skillScoreUsesProficiencyAndOptionalBonus() {
        WorkloadResponse w = workload(0, 5, 0, null, null, null);
        TicketSlaResponse sla = sla("ON_TRACK", 720);

        assertEquals(70, new SkillMatchStrategy().score(
                input(w, sla, Set.of(), Set.of(), Map.of())));

        assertEquals(90, new SkillMatchStrategy().score(
                input(w, sla, Set.of(1L), Set.of(), Map.of(1L, 3))));

        // 0.7 × 90 + 0.3 × 100 = 93
        assertEquals(93, new SkillMatchStrategy().score(
                input(w, sla, Set.of(1L), Set.of(2L), Map.of(1L, 3, 2L, 4))));
    }

    @Test
    void workloadScoreDecreasesWithOccupancy() {
        WorkloadScoreStrategy strategy = new WorkloadScoreStrategy();
        TicketSlaResponse sla = sla("ON_TRACK", 720);
        assertEquals(100, strategy.score(input(workload(0, 5, 0, null, null, null), sla,
                Set.of(), Set.of(), Map.of())));
        assertEquals(50, strategy.score(input(workload(2, 4, 0, null, null, null), sla,
                Set.of(), Set.of(), Map.of())));
        assertEquals(0, strategy.score(input(workload(5, 5, 0, null, null, null), sla,
                Set.of(), Set.of(), Map.of())));
        // 超出上限同样钳制到 0，不允许负分
        assertEquals(0, strategy.score(input(workload(9, 5, 0, null, null, null), sla,
                Set.of(), Set.of(), Map.of())));
    }

    @Test
    void slaUrgencyCoversAllStates() {
        assertEquals(100, SlaUrgencyScoreStrategy.urgency(sla("OVERDUE", -30), 24));
        assertEquals(0, SlaUrgencyScoreStrategy.urgency(sla("COMPLETED", 0), 24));
        // 预警剩余 60 分钟（预警窗口 120）：70 + 30 × 0.5 = 85
        assertEquals(85, SlaUrgencyScoreStrategy.urgency(sla("WARNING", 60), 24));
        // 正常且剩余等于整个 SLA 时长：紧迫度 0
        assertEquals(0, SlaUrgencyScoreStrategy.urgency(sla("ON_TRACK", 1440), 24));
        // 正常且剩余一半：70 × 0.5 = 35
        assertEquals(35, SlaUrgencyScoreStrategy.urgency(sla("ON_TRACK", 720), 24));
    }

    @Test
    void performanceUsesNeutralScoreWhenSampleIsNotEnough() {
        PerformanceScoreStrategy strategy = new PerformanceScoreStrategy(properties);
        TicketSlaResponse sla = sla("ON_TRACK", 720);
        assertEquals(60, strategy.score(input(workload(0, 5, 4, 100.0, 30L, 60.0), sla,
                Set.of(), Set.of(), Map.of())));
    }

    @Test
    void performanceCombinesVolumeSlaAndSpeed() {
        PerformanceScoreStrategy strategy = new PerformanceScoreStrategy(properties);
        TicketSlaResponse sla = sla("ON_TRACK", 720);
        // 完成 10 单 → 100；SLA 达成 100；用时 60/120 → 速度 50 → 40 + 40 + 10 = 90
        assertEquals(90, strategy.score(input(workload(0, 5, 10, 100.0, 60L, 120.0), sla,
                Set.of(), Set.of(), Map.of())));
        // 完成 5 单 → 50；SLA 达成 80；用时与 SLA 持平 → 速度 0 → 20 + 32 + 0 = 52
        assertEquals(52, strategy.score(input(workload(0, 5, 5, 80.0, 120L, 120.0), sla,
                Set.of(), Set.of(), Map.of())));
    }

    @Test
    void totalIsWeightedSumOfFourDimensions() {
        WorkloadResponse w = workload(0, 5, 0, null, null, null);
        ScoreBreakdown breakdown = scoring.score(
                input(w, sla("OVERDUE", -10), Set.of(1L), Set.of(), Map.of(1L, 3)));
        assertEquals(90, breakdown.skillScore());
        assertEquals(100, breakdown.workloadScore());
        assertEquals(100, breakdown.slaScore());
        assertEquals(60, breakdown.performanceScore());
        // 90×0.4 + 100×0.3 + 100×0.2 + 60×0.1 = 92
        assertEquals(92, breakdown.total());
    }
}

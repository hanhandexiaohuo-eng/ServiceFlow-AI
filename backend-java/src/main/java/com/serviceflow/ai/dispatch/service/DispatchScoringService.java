package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.strategy.ScoreInput;
import com.serviceflow.ai.dispatch.strategy.ScoreStrategy;
import com.serviceflow.ai.dispatch.vo.DispatchWeightsResponse;
import com.serviceflow.ai.dispatch.vo.ScoreBreakdown;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 汇总四个维度并按配置权重计算总分；权重自动归一化。 */
@Service
public class DispatchScoringService {

    private final Map<String, ScoreStrategy> strategies = new LinkedHashMap<>();
    private final DispatchProperties properties;

    public DispatchScoringService(List<ScoreStrategy> strategyList, DispatchProperties properties) {
        this.properties = properties;
        strategyList.forEach(s -> strategies.put(s.dimension(), s));
    }

    public ScoreBreakdown score(ScoreInput input) {
        double skill = round1(scoreOf("skill", input));
        double workload = round1(scoreOf("workload", input));
        double sla = round1(scoreOf("sla", input));
        double performance = round1(scoreOf("performance", input));

        DispatchWeightsResponse weights = normalizedWeights();
        double total = round1(skill * weights.skill()
                + workload * weights.workload()
                + sla * weights.sla()
                + performance * weights.performance());
        return new ScoreBreakdown(skill, workload, sla, performance, total,
                weights.skill(), weights.workload(), weights.sla(), weights.performance());
    }

    public DispatchWeightsResponse normalizedWeights() {
        DispatchProperties.Weights w = properties.getWeights();
        double sum = w.sum();
        if (sum <= 0) {
            sum = 1;
        }
        return new DispatchWeightsResponse(
                round4(w.getSkill() / sum),
                round4(w.getWorkload() / sum),
                round4(w.getSla() / sum),
                round4(w.getPerformance() / sum));
    }

    private double scoreOf(String dimension, ScoreInput input) {
        ScoreStrategy strategy = strategies.get(dimension);
        if (strategy == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, strategy.score(input)));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}

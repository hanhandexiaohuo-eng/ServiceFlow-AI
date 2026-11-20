package com.serviceflow.ai.dispatch.strategy;

import com.serviceflow.ai.dispatch.config.DispatchProperties;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import org.springframework.stereotype.Component;

/**
 * 历史表现：基于真实完成记录。
 * 样本低于门槛时使用中性分，避免新员工因无数据被永久排除。
 * 0.4 × 完成量 + 0.4 × SLA 达成率 + 0.2 × 速度。
 */
@Component
public class PerformanceScoreStrategy implements ScoreStrategy {

    private final DispatchProperties properties;

    public PerformanceScoreStrategy(DispatchProperties properties) {
        this.properties = properties;
    }

    @Override
    public String dimension() {
        return "performance";
    }

    @Override
    public double score(ScoreInput input) {
        DispatchProperties.Performance config = properties.getPerformance();
        WorkloadResponse workload = input.workload();
        if (workload.completedSample() < config.getSampleThreshold()) {
            return config.getNeutralScore();
        }
        double volume = Math.min(100,
                workload.completedSample() * 100.0 / Math.max(1, config.getFullVolume()));
        double slaRate = workload.slaAchievedRate() == null
                ? config.getNeutralScore() : workload.slaAchievedRate();
        double speed;
        if (workload.avgHandlingMinutes() == null || workload.avgSlaMinutes() == null
                || workload.avgSlaMinutes() <= 0) {
            speed = 60;
        } else {
            speed = Math.max(0, Math.min(100,
                    100 * (1 - workload.avgHandlingMinutes() / workload.avgSlaMinutes())));
        }
        return Math.max(0, Math.min(100, volume * 0.4 + slaRate * 0.4 + speed * 0.2));
    }
}

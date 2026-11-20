package com.serviceflow.ai.dispatch.strategy;

import org.springframework.stereotype.Component;

/** 负载越低分越高：WorkloadScore = clamp(0,100, 100 × (1 - 占用 / 上限))。 */
@Component
public class WorkloadScoreStrategy implements ScoreStrategy {

    @Override
    public String dimension() {
        return "workload";
    }

    @Override
    public double score(ScoreInput input) {
        int max = Math.max(1, input.workload().maxConcurrent());
        double rate = (double) input.workload().occupied() / max;
        return Math.max(0, Math.min(100, 100 * (1 - rate)));
    }
}

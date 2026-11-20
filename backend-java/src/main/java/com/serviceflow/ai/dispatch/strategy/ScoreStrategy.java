package com.serviceflow.ai.dispatch.strategy;

/** 单一评分维度，返回 0-100。 */
public interface ScoreStrategy {

    /** 维度标识：skill / workload / sla / performance */
    String dimension();

    double score(ScoreInput input);
}

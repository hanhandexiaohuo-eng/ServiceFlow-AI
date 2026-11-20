package com.serviceflow.ai.dispatch.vo;

/** 四项评分与加权总分，所有分项均为 0-100。 */
public record ScoreBreakdown(
        double skillScore,
        double workloadScore,
        double slaScore,
        double performanceScore,
        double total,
        double skillWeight,
        double workloadWeight,
        double slaWeight,
        double performanceWeight
) {
}

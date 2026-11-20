package com.serviceflow.ai.dispatch.vo;

/** 实际参与计算的权重（已归一化）。 */
public record DispatchWeightsResponse(
        double skill,
        double workload,
        double sla,
        double performance
) {
}

package com.serviceflow.ai.ticket.dto;

/** SLA 策略响应：当前策略由代码定义，只读展示。 */
public record SlaPolicyResponse(
        String priority,
        int hours,
        int warningMinutes,
        String label
) {
}

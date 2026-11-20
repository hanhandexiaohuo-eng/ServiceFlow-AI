package com.serviceflow.ai.dispatch.enums;

/**
 * 人员工作状态。与 users.status（账号是否启用）严格区分：
 * 账号被禁用时，无论此字段为何值，有效状态一律视为 DISABLED。
 */
public enum AgentAvailability {
    AVAILABLE("可接单"),
    BUSY("繁忙"),
    OFFLINE("不可用"),
    DISABLED("已禁用");

    private final String label;

    AgentAvailability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AgentAvailability parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("人员状态不合法：" + value);
        }
    }
}

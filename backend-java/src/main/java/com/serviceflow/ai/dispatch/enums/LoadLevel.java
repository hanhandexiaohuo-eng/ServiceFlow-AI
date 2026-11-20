package com.serviceflow.ai.dispatch.enums;

/**
 * 负载等级。阈值来自 dispatch.load.* 配置：
 * LOW: rate <= lowMax；NORMAL: <= normalMax；HIGH: <= highMax；其余 FULL。
 */
public enum LoadLevel {
    LOW("低负载"),
    NORMAL("正常负载"),
    HIGH("高负载"),
    FULL("满负载");

    private final String label;

    LoadLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static LoadLevel of(double rate, int lowMax, int normalMax, int highMax) {
        if (rate <= lowMax) {
            return LOW;
        }
        if (rate <= normalMax) {
            return NORMAL;
        }
        if (rate <= highMax) {
            return HIGH;
        }
        return FULL;
    }
}

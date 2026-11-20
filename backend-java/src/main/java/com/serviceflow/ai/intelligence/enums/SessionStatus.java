package com.serviceflow.ai.intelligence.enums;

/** 会话状态：归档后不可继续追问。 */
public enum SessionStatus {
    ACTIVE,
    ARCHIVED;

    public static SessionStatus parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的会话状态：" + value);
        }
    }
}

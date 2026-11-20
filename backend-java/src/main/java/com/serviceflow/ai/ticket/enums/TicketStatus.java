package com.serviceflow.ai.ticket.enums;

/**
 * 统一工单状态。第三阶段由 4 状态扩展为 7 状态，数据库旧值已在迁移脚本中映射：
 * OPEN 有处理人 → ASSIGNED，OPEN 无处理人 → PENDING，IN_PROGRESS → PROCESSING。
 */
public enum TicketStatus {
    PENDING("待分配"),
    ASSIGNED("已分配"),
    ACCEPTED("已接单"),
    PROCESSING("处理中"),
    RESOLVED("已解决"),
    CLOSED("已关闭"),
    CANCELLED("已取消");

    private final String label;

    TicketStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 终态：不再允许任何流转 */
    public boolean terminal() {
        return this == CLOSED || this == CANCELLED;
    }

    public boolean active() {
        return !terminal();
    }

    public static TicketStatus parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("状态不合法：" + value);
        }
    }
}

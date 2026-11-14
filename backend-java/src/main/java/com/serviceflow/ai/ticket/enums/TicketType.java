package com.serviceflow.ai.ticket.enums;

/** 工单类型 */
public enum TicketType {
    INCIDENT("故障申报"),
    SERVICE_REQUEST("服务请求"),
    CHANGE("变更申请"),
    CONSULT("业务咨询");

    private final String label;

    TicketType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

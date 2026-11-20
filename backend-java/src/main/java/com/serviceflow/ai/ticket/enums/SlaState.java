package com.serviceflow.ai.ticket.enums;

/** SLA 计时状态：完成态不再参与超时判定。 */
public enum SlaState {
    ON_TRACK,
    WARNING,
    OVERDUE,
    COMPLETED
}

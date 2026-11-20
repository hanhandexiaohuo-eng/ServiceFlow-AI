package com.serviceflow.ai.ticket.enums;

/** 工单事件类型：详情页时间线与操作记录直接读取该字段。 */
public enum TicketEventType {
    CREATE,
    UPDATE,
    ASSIGN,
    TRANSFER,
    ACCEPT,
    START,
    RESOLVE,
    CLOSE,
    CANCEL
}

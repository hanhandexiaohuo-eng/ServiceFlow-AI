package com.serviceflow.ai.ticket.enums;

/** 工作流动作：所有状态变更只能通过这 7 个动作完成，禁止客户端直接写 status。 */
public enum TicketAction {
    ASSIGN,
    TRANSFER,
    ACCEPT,
    START,
    RESOLVE,
    CLOSE,
    CANCEL
}

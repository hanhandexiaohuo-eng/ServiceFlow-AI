package com.serviceflow.ai.dispatch.vo;

import com.serviceflow.ai.ticket.dto.TicketResponse;

/** 确认分配结果：工单已通过工作流完成 ASSIGN，并写入调度日志。 */
public record DispatchAssignResponse(
        TicketResponse ticket,
        Long logId,
        DispatchCandidateResponse chosen
) {
}

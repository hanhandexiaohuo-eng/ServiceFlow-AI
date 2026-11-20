package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.Size;

/** 取消工单，可附原因。 */
public record TicketCancelRequest(
        @Size(max = 500, message = "取消原因不能超过 500 字")
        String reason
) {
}

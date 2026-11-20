package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.Size;

/** 关闭工单，可附备注。 */
public record TicketCloseRequest(
        @Size(max = 500, message = "关闭备注不能超过 500 字")
        String note
) {
}

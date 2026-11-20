package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.Size;

/** 解决工单，可附带解决说明。 */
public record TicketResolveRequest(
        @Size(max = 1000, message = "解决说明不能超过 1000 字")
        String resolution
) {
}

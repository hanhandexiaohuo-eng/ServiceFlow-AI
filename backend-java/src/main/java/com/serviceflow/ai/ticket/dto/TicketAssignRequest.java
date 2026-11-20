package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 分配/转交处理人。处理人必须是 users 表中启用的真实用户。 */
public record TicketAssignRequest(
        @NotBlank(message = "处理人不能为空")
        @Size(max = 64, message = "处理人不能超过 64 字")
        String assignee
) {
}

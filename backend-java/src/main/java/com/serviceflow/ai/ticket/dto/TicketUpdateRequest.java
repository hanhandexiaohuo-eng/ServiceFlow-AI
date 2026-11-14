package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新工单请求。采用全量更新（PUT 语义）：客户端提交完整字段。
 */
public record TicketUpdateRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题不能超过 200 字")
        String title,

        String description,

        @NotBlank(message = "工单类型不能为空")
        String type,

        @NotBlank(message = "优先级不能为空")
        String priority,

        @NotBlank(message = "状态不能为空")
        String status,

        @Size(max = 64, message = "处理人不能超过 64 字")
        String assignee
) {
}

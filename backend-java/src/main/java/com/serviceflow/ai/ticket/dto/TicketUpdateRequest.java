package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑工单内容。状态与处理人只能通过工作流接口变更：
 * 这里保留字段仅用于识别旧调用并返回 400，避免客户端静默写入无效值。
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

        String status,

        @Size(max = 64, message = "处理人不能超过 64 字")
        String assignee
) {
}

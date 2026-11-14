package com.serviceflow.ai.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建工单请求。用 record：不可变、自动生成构造器/getter。
 * @NotBlank/@Size 由 Controller 上的 @Valid 触发，失败抛 MethodArgumentNotValidException。
 * type/priority 不在这里注解枚举值，由 Service 层用 valueOf 校验并给出中文提示。
 */
public record TicketCreateRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题不能超过 200 字")
        String title,

        String description,

        @NotBlank(message = "工单类型不能为空")
        String type,

        String priority,

        @Size(max = 64, message = "处理人不能超过 64 字")
        String assignee
) {
}
// 注意：creator 不在请求体中，由服务端从登录上下文（UserContext）取当前用户显示名，防止伪造。

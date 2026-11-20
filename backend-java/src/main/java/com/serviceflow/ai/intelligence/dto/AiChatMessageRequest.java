package com.serviceflow.ai.intelligence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 在会话中追问。内容属于不可信输入，由 Python 侧定界包裹。 */
public record AiChatMessageRequest(
        @NotBlank(message = "追问内容不能为空")
        @Size(max = 2000, message = "追问内容不能超过 2000 字")
        String content
) {
}

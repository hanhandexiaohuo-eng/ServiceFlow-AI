package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新增自定义技能，skillCode 可省略，由服务端按名称归一化生成。 */
public record SkillCreateRequest(
        @NotBlank(message = "技能名称不能为空")
        @Size(max = 64, message = "技能名称最长 64 个字符") String skillName,
        @Size(max = 32, message = "技能编码最长 32 个字符") String skillCode,
        @Size(max = 255, message = "技能说明最长 255 个字符") String description
) {
}

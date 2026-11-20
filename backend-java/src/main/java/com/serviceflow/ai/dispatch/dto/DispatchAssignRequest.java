package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 人工确认分配：推荐结果只是建议，最终必须经过此接口并由服务端重新校验。 */
public record DispatchAssignRequest(
        @NotNull(message = "工单不能为空") Long ticketId,
        @NotNull(message = "处理人不能为空") Long agentId,
        @Size(max = 500, message = "备注最长 500 个字符") String note
) {
}

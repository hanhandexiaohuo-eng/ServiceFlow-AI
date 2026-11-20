package com.serviceflow.ai.dispatch.vo;

import java.util.List;

/** 被排除的候选及原因，不进入正常推荐名单。 */
public record DispatchExcludedResponse(
        Long agentId,
        String name,
        List<String> reasons
) {
}

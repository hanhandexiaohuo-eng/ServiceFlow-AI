package com.serviceflow.ai.intelligence.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.intelligence.service.AiContextService;
import com.serviceflow.ai.intelligence.vo.AiTicketContextResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 工作空间右侧栏：工单上下文、最近分析、待确认建议、知识与调度快照。 */
@Tag(name = "10 AI 工单上下文", description = "AI 分析所需的工单上下文")
@RestController
@RequestMapping("/api/ai/tickets")
public class AiTicketContextController {

    private final AiContextService contextService;

    public AiTicketContextController(AiContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping("/{ticketId}/context")
    public ApiResponse<AiTicketContextResponse> context(@PathVariable Long ticketId) {
        return ApiResponse.ok(contextService.context(ticketId));
    }
}

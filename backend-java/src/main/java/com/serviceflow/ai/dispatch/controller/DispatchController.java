package com.serviceflow.ai.dispatch.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.dispatch.dto.DispatchAssignRequest;
import com.serviceflow.ai.dispatch.service.DispatchService;
import com.serviceflow.ai.dispatch.vo.DispatchAssignResponse;
import com.serviceflow.ai.dispatch.vo.DispatchLogResponse;
import com.serviceflow.ai.dispatch.vo.DispatchRecommendationResponse;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 调度工作台：推荐只读，确认分配最终走工单工作流。 */
@Tag(name = "07 智能调度", description = "推荐、分配、转派与调度历史")
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @GetMapping("/tickets")
    public ApiResponse<PageResponse<TicketResponse>> pendingTickets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(dispatchService.pendingTickets(page, size));
    }

    @GetMapping("/recommendations/{ticketId}")
    public ApiResponse<DispatchRecommendationResponse> recommendations(
            @PathVariable Long ticketId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(dispatchService.recommend(ticketId, limit));
    }

    @PostMapping("/assign")
    public ResponseEntity<ApiResponse<DispatchAssignResponse>> assign(
            @Valid @RequestBody DispatchAssignRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(dispatchService.assign(req)));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<DispatchLogResponse>> history(
            @RequestParam(required = false) Long ticketId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(dispatchService.history(ticketId, agentId, page, size));
    }
}

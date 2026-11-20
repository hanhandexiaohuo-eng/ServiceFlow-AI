package com.serviceflow.ai.intelligence.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.intelligence.dto.AiChatMessageRequest;
import com.serviceflow.ai.intelligence.dto.AiSessionCreateRequest;
import com.serviceflow.ai.intelligence.dto.AiSessionUpdateRequest;
import com.serviceflow.ai.intelligence.service.AiSessionService;
import com.serviceflow.ai.intelligence.vo.AiChatResponse;
import com.serviceflow.ai.intelligence.vo.AiSessionDetailResponse;
import com.serviceflow.ai.intelligence.vo.AiSessionResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 会话与追问。 */
@Tag(name = "09 AI 会话", description = "会话创建、消息与建议反馈")
@RestController
@RequestMapping("/api/ai/sessions")
@Validated
public class AiSessionController {

    private final AiSessionService sessionService;

    public AiSessionController(AiSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AiSessionResponse>> create(
            @Valid @RequestBody AiSessionCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(sessionService.create(req)));
    }

    @GetMapping
    public ApiResponse<PageResponse<AiSessionResponse>> page(
            @RequestParam(required = false) Long ticketId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 最小为 1") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "size 最小为 1")
            @Max(value = 100, message = "size 最大为 100") int size) {
        return ApiResponse.ok(sessionService.page(ticketId, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AiSessionDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiSessionResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody AiSessionUpdateRequest req) {
        return ApiResponse.ok(sessionService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        sessionService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 追问：写 USER + ASSISTANT 两条消息，AI 不可用时返回 5xx 且不写假回复 */
    @PostMapping("/{id}/messages")
    public ApiResponse<AiChatResponse> ask(@PathVariable Long id,
                                           @Valid @RequestBody AiChatMessageRequest req) {
        return ApiResponse.ok(sessionService.ask(id, req.content()));
    }
}

package com.serviceflow.ai.intelligence.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.intelligence.dto.AiAnalysisRequest;
import com.serviceflow.ai.intelligence.dto.SuggestionDecisionRequest;
import com.serviceflow.ai.intelligence.service.AiAnalysisService;
import com.serviceflow.ai.intelligence.service.AiSuggestionService;
import com.serviceflow.ai.intelligence.vo.AiAnalysisBrief;
import com.serviceflow.ai.intelligence.vo.AiAnalysisResponse;
import com.serviceflow.ai.intelligence.vo.AiSuggestionResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 分析与建议接口。所有业务权限判断在服务层完成，
 * Controller 只做参数校验与响应组装。
 */
@Tag(name = "08 AI 分析", description = "工单 AI 分析与建议采纳")
@RestController
@RequestMapping("/api/ai")
@Validated
public class AiAnalysisController {

    private final AiAnalysisService analysisService;
    private final AiSuggestionService suggestionService;

    public AiAnalysisController(AiAnalysisService analysisService,
                                AiSuggestionService suggestionService) {
        this.analysisService = analysisService;
        this.suggestionService = suggestionService;
    }

    /** 发起 AI 分析（分类 / 优先级 / 方案 / 调度辅助） */
    @PostMapping("/analyses")
    public ResponseEntity<ApiResponse<AiAnalysisResponse>> analyze(
            @Valid @RequestBody AiAnalysisRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(analysisService.analyze(req)));
    }

    /** 分析历史：管理员全部，其他人仅本人发起 */
    @GetMapping("/analyses")
    public ApiResponse<PageResponse<AiAnalysisBrief>> page(
            @RequestParam(required = false) Long ticketId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 最小为 1") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "size 最小为 1")
            @Max(value = 100, message = "size 最大为 100") int size) {
        return ApiResponse.ok(analysisService.page(ticketId, type, status, page, size));
    }

    /** 分析详情（含建议与知识推荐） */
    @GetMapping("/analyses/{id}")
    public ApiResponse<AiAnalysisResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(analysisService.get(id));
    }

    /** 采纳建议：服务端重新鉴权后调用既有业务接口 */
    @PostMapping("/suggestions/{id}/accept")
    public ApiResponse<AiSuggestionResponse> accept(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SuggestionDecisionRequest req) {
        return ApiResponse.ok(suggestionService.accept(id, req == null ? null : req.note()));
    }

    /** 拒绝建议 */
    @PostMapping("/suggestions/{id}/reject")
    public ApiResponse<AiSuggestionResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SuggestionDecisionRequest req) {
        return ApiResponse.ok(suggestionService.reject(id, req == null ? null : req.note()));
    }

    @GetMapping("/suggestions/{id}")
    public ApiResponse<AiSuggestionResponse> suggestion(@PathVariable Long id) {
        return ApiResponse.ok(suggestionService.get(id));
    }
}

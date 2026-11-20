package com.serviceflow.ai.knowledge.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.knowledge.dto.KnowledgeLinkRequest;
import com.serviceflow.ai.knowledge.dto.KnowledgeSaveRequest;
import com.serviceflow.ai.knowledge.service.KnowledgeService;
import com.serviceflow.ai.knowledge.vo.KnowledgeArticleResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeCategoryResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeLinkResponse;
import com.serviceflow.ai.knowledge.vo.KnowledgeSummaryResponse;
import com.serviceflow.ai.knowledge.vo.LinkedTicketResponse;
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

import java.util.List;

/** 知识库：关键词检索与工单关联，第一版不使用向量检索。 */
@Tag(name = "11 知识库", description = "知识条目检索与维护")
@RestController
@RequestMapping("/api/knowledge")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ApiResponse<PageResponse<KnowledgeSummaryResponse>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 最小为 1") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "size 最小为 1")
            @Max(value = 100, message = "size 最大为 100") int size) {
        return ApiResponse.ok(knowledgeService.page(keyword, category, status, page, size));
    }

    @GetMapping("/categories")
    public ApiResponse<List<KnowledgeCategoryResponse>> categories() {
        return ApiResponse.ok(knowledgeService.categories());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeArticleResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeService.get(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KnowledgeArticleResponse>> create(
            @Valid @RequestBody KnowledgeSaveRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(knowledgeService.create(req)));
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeArticleResponse> update(@PathVariable Long id,
                                                        @Valid @RequestBody KnowledgeSaveRequest req) {
        return ApiResponse.ok(knowledgeService.update(id, req));
    }

    @GetMapping("/{id}/tickets")
    public ApiResponse<List<LinkedTicketResponse>> linkedTickets(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeService.linkedTickets(id));
    }

    @PostMapping("/{id}/tickets")
    public ApiResponse<KnowledgeLinkResponse> link(@PathVariable Long id,
                                                   @Valid @RequestBody KnowledgeLinkRequest req) {
        return ApiResponse.ok(knowledgeService.link(id, req));
    }

    @DeleteMapping("/{id}/tickets/{ticketId}")
    public ApiResponse<Void> unlink(@PathVariable Long id, @PathVariable Long ticketId) {
        knowledgeService.unlink(id, ticketId);
        return ApiResponse.ok(null);
    }

    /** 工单关联的知识（AI 工作空间/详情页复用） */
    @GetMapping("/tickets/{ticketId}")
    public ApiResponse<List<KnowledgeLinkResponse>> linksForTicket(@PathVariable Long ticketId) {
        return ApiResponse.ok(knowledgeService.linksForTicket(ticketId));
    }
}

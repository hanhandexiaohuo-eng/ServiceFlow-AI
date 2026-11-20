package com.serviceflow.ai.ticket.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.ticket.dto.TicketAssignRequest;
import com.serviceflow.ai.ticket.dto.TicketCancelRequest;
import com.serviceflow.ai.ticket.dto.TicketCloseRequest;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketEventResponse;
import com.serviceflow.ai.ticket.dto.TicketResolveRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.service.TicketService;
import com.serviceflow.ai.ticket.service.TicketWorkflowService;
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

/**
 * 工单接口。CRUD 与工作流动作分开：
 * 状态只能通过 /assign /accept /start /resolve /close /cancel /transfer 变更，
 * PUT 只负责内容编辑。Controller 依旧不写业务判断。
 */
@Tag(name = "03 工单管理", description = "工单创建、查询、编辑、流转与事件")
@RestController
@RequestMapping("/api/tickets")
@Validated
public class TicketController {

    private final TicketService ticketService;
    private final TicketWorkflowService workflowService;

    public TicketController(TicketService ticketService, TicketWorkflowService workflowService) {
        this.ticketService = ticketService;
        this.workflowService = workflowService;
    }

    /** 创建工单 → 201 */
    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> create(@Valid @RequestBody TicketCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(ticketService.create(req)));
    }

    /** 分页查询，可选 status / priority 筛选 */
    @GetMapping
    public ApiResponse<PageResponse<TicketResponse>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 最小为 1") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "size 最小为 1")
            @Max(value = 100, message = "size 最大为 100") int size) {
        return ApiResponse.ok(ticketService.page(status, priority, page, size));
    }

    /** 查询详情 */
    @GetMapping("/{id}")
    public ApiResponse<TicketResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.getById(id));
    }

    /** 编辑内容（状态/处理人不可通过该接口变更） */
    @PutMapping("/{id}")
    public ApiResponse<TicketResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody TicketUpdateRequest req) {
        return ApiResponse.ok(ticketService.update(id, req));
    }

    /** 删除（仅 ADMIN，级联删除事件） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 查看流转事件（详情页时间线/操作记录） */
    @GetMapping("/{id}/events")
    public ApiResponse<List<TicketEventResponse>> events(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.listEvents(id));
    }

    /* ---------------- 工作流动作：服务端状态机校验 ---------------- */

    @PostMapping("/{id}/assign")
    public ApiResponse<TicketResponse> assign(@PathVariable Long id,
                                              @Valid @RequestBody TicketAssignRequest req) {
        return ApiResponse.ok(ticketService.toResponse(workflowService.assign(id, req.assignee())));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<TicketResponse> transfer(@PathVariable Long id,
                                                @Valid @RequestBody TicketAssignRequest req) {
        return ApiResponse.ok(ticketService.toResponse(workflowService.transfer(id, req.assignee())));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<TicketResponse> accept(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.toResponse(workflowService.accept(id)));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<TicketResponse> start(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.toResponse(workflowService.start(id)));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<TicketResponse> resolve(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) TicketResolveRequest req) {
        return ApiResponse.ok(ticketService.toResponse(
                workflowService.resolve(id, req == null ? null : req.resolution())));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<TicketResponse> close(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) TicketCloseRequest req) {
        return ApiResponse.ok(ticketService.toResponse(
                workflowService.close(id, req == null ? null : req.note())));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<TicketResponse> cancel(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) TicketCancelRequest req) {
        return ApiResponse.ok(ticketService.toResponse(
                workflowService.cancel(id, req == null ? null : req.reason())));
    }
}

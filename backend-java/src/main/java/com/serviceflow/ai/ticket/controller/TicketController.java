package com.serviceflow.ai.ticket.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.ticket.dto.TicketCreateRequest;
import com.serviceflow.ai.ticket.dto.TicketResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.service.TicketService;
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

/**
 * 工单接口。职责只有三件：接收参数 → @Valid 触发校验 → 调用 Service 组装响应。
 * 不写任何业务判断（那是 Service 的事）。
 *
 * @Validated 加在类上是为了让 @RequestParam 上的 @Min/@Max 生效。
 */
@RestController
@RequestMapping("/api/tickets")
@Validated
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
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

    /** 全量更新 */
    @PutMapping("/{id}")
    public ApiResponse<TicketResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody TicketUpdateRequest req) {
        return ApiResponse.ok(ticketService.update(id, req));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return ApiResponse.ok(null);
    }
}

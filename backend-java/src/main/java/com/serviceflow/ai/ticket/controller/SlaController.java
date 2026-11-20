package com.serviceflow.ai.ticket.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.ticket.dto.SlaPolicyResponse;
import com.serviceflow.ai.ticket.service.TicketSlaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** SLA 策略只读接口。时限与预警阈值来自配置（sla.*），调整无需改代码。 */
@Tag(name = "04 SLA 管理", description = "SLA 策略与工单 SLA 评估")
@RestController
@RequestMapping("/api/sla")
public class SlaController {

    private final TicketSlaService slaService;

    public SlaController(TicketSlaService slaService) {
        this.slaService = slaService;
    }

    @GetMapping("/policies")
    public ApiResponse<List<SlaPolicyResponse>> policies() {
        return ApiResponse.ok(slaService.policies());
    }
}

package com.serviceflow.ai.health.controller;

import com.serviceflow.ai.health.dto.ReadinessResponse;
import com.serviceflow.ai.health.service.HealthService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "17 健康检查", description = "存活与就绪检查")
@SecurityRequirements
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /** 公网存活检查只返回最小化状态，不暴露服务名、版本与依赖细节 */
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * 就绪检查含依赖细节，仅供容器/负载均衡/内网监控使用：
     * 生产由 nginx 拒绝外部访问，容器健康检查直接访问后端容器。
     */
    @GetMapping("/ready")
    public ResponseEntity<ReadinessResponse> ready() {
        ReadinessResponse response = healthService.readiness();
        // MySQL 不可用 → 503；仅 AI 不可用（DEGRADED）仍返回 200，核心业务可继续服务
        HttpStatus status = "DOWN".equals(response.status())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}

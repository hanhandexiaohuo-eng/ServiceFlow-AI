package com.serviceflow.ai.health.controller;

import com.serviceflow.ai.health.dto.HealthResponse;
import com.serviceflow.ai.health.dto.ReadinessResponse;
import com.serviceflow.ai.health.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public HealthResponse health() {
        return healthService.health();
    }

    @GetMapping("/ready")
    public ResponseEntity<ReadinessResponse> ready() {
        ReadinessResponse response = healthService.readiness();
        HttpStatus status = "UP".equals(response.status())
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}

package com.serviceflow.ai.health.dto;

import java.time.Instant;

public record HealthResponse(
        String service,
        String status,
        String version,
        Instant timestamp
) {
}

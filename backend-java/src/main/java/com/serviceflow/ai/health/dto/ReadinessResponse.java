package com.serviceflow.ai.health.dto;

import java.time.Instant;
import java.util.List;

public record ReadinessResponse(
        String service,
        String status,
        String version,
        Instant timestamp,
        List<ComponentCheck> checks
) {
}

package com.serviceflow.ai.health.dto;

public record ComponentCheck(
        String name,
        String status,
        String message
) {
}

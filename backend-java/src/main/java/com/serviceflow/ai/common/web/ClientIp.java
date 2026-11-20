package com.serviceflow.ai.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 解析客户端来源 IP：优先取 nginx/负载均衡写入的 X-Forwarded-For 第一段。
 * 仅用于审计与限流，不参与鉴权判定。
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && realIp.length() <= 64) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

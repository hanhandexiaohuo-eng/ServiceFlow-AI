package com.serviceflow.ai.intelligence.governance.service;

/** 管理端请求元信息：用于审计来源 IP 与请求 ID，不包含任何凭据。 */
public record AiRequestMeta(String ip, String requestId) {
}

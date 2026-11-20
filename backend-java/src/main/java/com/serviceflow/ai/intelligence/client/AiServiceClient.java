package com.serviceflow.ai.intelligence.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.common.web.RequestIdFilter;
import com.serviceflow.ai.intelligence.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Python AI 服务客户端。
 * 只记录 requestId/路径/耗时/状态，不记录密钥、Token、Prompt 或工单正文。
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final AiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiServiceClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    public JsonNode call(String path, Map<String, Object> body) {
        if (!properties.isEnabled()) {
            throw new AiServiceException("AI_DISABLED", "AI 服务已在配置中禁用", 503);
        }
        long started = System.currentTimeMillis();
        try {
            var spec = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            // 把当前请求的追踪 ID 传给 Python 服务，便于跨服务按同一 ID 排查
            String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
            if (requestId != null) {
                spec = spec.header(RequestIdFilter.HEADER, requestId);
            }
            JsonNode response = spec.body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.has("result") || !response.get("result").isObject()) {
                throw new AiServiceException("AI_INVALID_OUTPUT", "AI 服务返回结构异常", 422);
            }
            log.info("ai_call path={} costMs={} status=SUCCESS", path, System.currentTimeMillis() - started);
            return response;
        } catch (AiServiceException e) {
            log.warn("ai_call path={} costMs={} status={} code={}",
                    path, System.currentTimeMillis() - started, e.getStatusCode(), e.getCode());
            throw e;
        } catch (RestClientResponseException e) {
            AiServiceException mapped = mapErrorResponse(e);
            log.warn("ai_call path={} costMs={} httpStatus={} code={}",
                    path, System.currentTimeMillis() - started, e.getStatusCode().value(), mapped.getCode());
            throw mapped;
        } catch (ResourceAccessException e) {
            String message = e.getCause() instanceof java.net.SocketTimeoutException
                    ? "AI 服务响应超时"
                    : "AI 服务不可用，请确认 Python 服务已启动";
            log.warn("ai_call path={} costMs={} status=NETWORK_ERROR type={}",
                    path, System.currentTimeMillis() - started, e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            throw new AiServiceException(
                    e.getCause() instanceof java.net.SocketTimeoutException ? "AI_TIMEOUT" : "AI_UNAVAILABLE",
                    message,
                    e.getCause() instanceof java.net.SocketTimeoutException ? 504 : 503);
        } catch (Exception e) {
            log.error("ai_call path={} costMs={} status=UNEXPECTED type={}",
                    path, System.currentTimeMillis() - started, e.getClass().getSimpleName());
            throw new AiServiceException("AI_UNAVAILABLE", "AI 调用失败，请稍后重试", 503);
        }
    }

    private AiServiceException mapErrorResponse(RestClientResponseException e) {
        String code = "AI_UPSTREAM_ERROR";
        String message = "AI 服务返回异常";
        try {
            JsonNode root = objectMapper.readTree(e.getResponseBodyAsString());
            JsonNode error = root.path("error");
            if (error.hasNonNull("code")) {
                code = error.get("code").asText();
            }
            if (error.hasNonNull("message")) {
                message = error.get("message").asText();
            }
        } catch (Exception ignored) {
            // 响应体不是约定结构时使用默认错误码
        }
        int status = e.getStatusCode().value();
        if (status == 429) {
            code = "AI_RATE_LIMITED";
            message = "AI 调用过于频繁，请稍后重试";
        } else if (status == 504) {
            code = "AI_TIMEOUT";
            message = "AI 服务响应超时，请重试";
        } else if (status == 503 && code.equals("AI_UPSTREAM_ERROR")) {
            message = "AI 服务不可用";
        } else if (status < 400 || status > 599) {
            status = 502;
        }
        return new AiServiceException(code, message, status);
    }
}

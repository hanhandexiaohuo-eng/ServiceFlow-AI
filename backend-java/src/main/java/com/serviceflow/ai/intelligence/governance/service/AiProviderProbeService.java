package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 供应商连通性测试：由服务端发起最小化探测，密钥只在本次请求头中使用。
 * 错误信息只包含状态码与分类，绝不回显密钥或供应商响应正文。
 */
@Service
public class AiProviderProbeService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderProbeService.class);

    public TestResult probe(String baseUrl, String apiKey, String model) {
        long started = System.currentTimeMillis();
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(10));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1,
                    "stream", false);
            client.post()
                    .uri(baseUrl + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new TestResult(true, "连接成功", System.currentTimeMillis() - started);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String message = switch (status) {
                case 401, 403 -> "鉴权失败（HTTP " + status + "）";
                case 404 -> "接口不存在（HTTP 404）";
                case 429 -> "供应商限流（HTTP 429）";
                default -> "供应商返回 HTTP " + status;
            };
            return new TestResult(false, message, System.currentTimeMillis() - started);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof SocketTimeoutException;
            return new TestResult(false, timeout ? "连接超时" : "网络不可达",
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("ai_provider_probe_failed type={}", e.getClass().getSimpleName());
            return new TestResult(false, "连接失败", System.currentTimeMillis() - started);
        }
    }
}

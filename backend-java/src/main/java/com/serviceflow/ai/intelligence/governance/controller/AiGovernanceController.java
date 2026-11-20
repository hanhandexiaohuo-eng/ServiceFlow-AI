package com.serviceflow.ai.intelligence.governance.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.web.ClientIp;
import com.serviceflow.ai.common.web.RequestIdFilter;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ModelCreate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ModelUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderCreate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderKeyUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.QuotaUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.RouteUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.StatusUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.TestRequest;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Model;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Provider;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Quota;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ScenarioRoute;
import com.serviceflow.ai.intelligence.governance.security.AiAccessPolicy;
import com.serviceflow.ai.intelligence.governance.service.AiGovernanceService;
import com.serviceflow.ai.intelligence.governance.service.AiRequestMeta;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallLogView;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallStats;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.TestResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 模型管理与调用治理（仅管理员）。
 * 供应商与密钥相关写操作仅超级管理员可执行；所有写操作记录审计，密钥只写不可读。
 */
@Tag(name = "12 AI 模型治理", description = "供应商、模型目录、场景路由、配额与调用统计（仅管理员）")
@RestController
@RequestMapping("/api/admin/ai")
@Validated
public class AiGovernanceController {

    private final AiGovernanceService service;
    private final AiAccessPolicy policy;

    public AiGovernanceController(AiGovernanceService service, AiAccessPolicy policy) {
        this.service = service;
        this.policy = policy;
    }

    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> meta() {
        policy.requireAdmin();
        return ApiResponse.ok(Map.of(
                "keyConfigured", service.keyConfigured(),
                "superAdmin", policy.isSuperAdmin()));
    }

    /* ---------------- 供应商 ---------------- */

    @GetMapping("/providers")
    public ApiResponse<List<Provider>> providers() {
        return ApiResponse.ok(service.listProviders());
    }

    @PostMapping("/providers")
    public ResponseEntity<ApiResponse<Provider>> createProvider(
            @Valid @RequestBody ProviderCreate req, HttpServletRequest request) {
        Provider created = service.createProvider(req, meta(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(created));
    }

    @PutMapping("/providers/{id}")
    public ApiResponse<Provider> updateProvider(@PathVariable Long id,
                                                @Valid @RequestBody ProviderUpdate req,
                                                HttpServletRequest request) {
        return ApiResponse.ok(service.updateProvider(id, req, meta(request)));
    }

    @PutMapping("/providers/{id}/key")
    public ApiResponse<Provider> updateProviderKey(@PathVariable Long id,
                                                   @Valid @RequestBody ProviderKeyUpdate req,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(service.updateProviderKey(id, req, meta(request)));
    }

    @PatchMapping("/providers/{id}/status")
    public ApiResponse<Provider> updateProviderStatus(@PathVariable Long id,
                                                      @RequestBody StatusUpdate req,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(service.setProviderStatus(id, Boolean.TRUE.equals(req.enabled()), meta(request)));
    }

    @PostMapping("/providers/{id}/test")
    public ApiResponse<TestResult> testProvider(@PathVariable Long id,
                                                @RequestBody(required = false) TestRequest req,
                                                HttpServletRequest request) {
        String modelCode = req == null ? null : req.modelCode();
        return ApiResponse.ok(service.testProvider(id, modelCode, meta(request)));
    }

    /* ---------------- 模型 ---------------- */

    @GetMapping("/models")
    public ApiResponse<List<Model>> models() {
        return ApiResponse.ok(service.listModels());
    }

    @PostMapping("/models")
    public ResponseEntity<ApiResponse<Model>> createModel(@Valid @RequestBody ModelCreate req,
                                                          HttpServletRequest request) {
        Model created = service.createModel(req, meta(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(created));
    }

    @PutMapping("/models/{id}")
    public ApiResponse<Model> updateModel(@PathVariable Long id,
                                          @Valid @RequestBody ModelUpdate req,
                                          HttpServletRequest request) {
        return ApiResponse.ok(service.updateModel(id, req, meta(request)));
    }

    @PatchMapping("/models/{id}/status")
    public ApiResponse<Model> updateModelStatus(@PathVariable Long id,
                                                @RequestBody StatusUpdate req,
                                                HttpServletRequest request) {
        return ApiResponse.ok(service.setModelStatus(id, Boolean.TRUE.equals(req.enabled()), meta(request)));
    }

    @PostMapping("/models/{id}/verify")
    public ApiResponse<Model> verifyModel(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.verifyModel(id, meta(request)));
    }

    @PostMapping("/models/{id}/default")
    public ApiResponse<Model> setDefaultModel(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.setDefaultModel(id, meta(request)));
    }

    /* ---------------- 场景路由 ---------------- */

    @GetMapping("/routes")
    public ApiResponse<List<ScenarioRoute>> routes() {
        return ApiResponse.ok(service.listRoutes());
    }

    @PutMapping("/routes/{scenario}")
    public ApiResponse<ScenarioRoute> updateRoute(@PathVariable String scenario,
                                                  @Valid @RequestBody RouteUpdate req,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(service.updateRoute(scenario, req, meta(request)));
    }

    /* ---------------- 配额与统计 ---------------- */

    @GetMapping("/quotas")
    public ApiResponse<List<Quota>> quotas() {
        return ApiResponse.ok(service.listQuotas());
    }

    @PutMapping("/quotas")
    public ApiResponse<Quota> upsertQuota(@Valid @RequestBody QuotaUpdate req,
                                          HttpServletRequest request) {
        return ApiResponse.ok(service.upsertQuota(req, meta(request)));
    }

    @DeleteMapping("/quotas/{id}")
    public ApiResponse<Void> deleteQuota(@PathVariable Long id, HttpServletRequest request) {
        service.deleteQuota(id, meta(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/call-stats")
    public ApiResponse<CallStats> callStats(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(service.stats(days));
    }

    @GetMapping("/calls")
    public ApiResponse<List<CallLogView>> calls(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.recentCalls(limit));
    }

    private AiRequestMeta meta(HttpServletRequest request) {
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(RequestIdFilter.HEADER);
        }
        return new AiRequestMeta(ClientIp.resolve(request), requestId);
    }
}

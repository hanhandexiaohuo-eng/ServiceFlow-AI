package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ModelCreate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ModelUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderCreate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderKeyUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.QuotaUpdate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.RouteUpdate;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Model;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Provider;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ProviderSecret;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Quota;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ScenarioRoute;
import com.serviceflow.ai.intelligence.governance.repository.AiGovernanceRepository;
import com.serviceflow.ai.intelligence.governance.security.AiAccessPolicy;
import com.serviceflow.ai.intelligence.governance.security.AiSecretCipher;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallLogView;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.CallStats;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 治理配置管理。
 * 权限边界：供应商与密钥、模型目录、默认模型、配额仅超级管理员可写；
 * 普通管理员可以调整场景路由参数与已批准模型的启停。
 * 所有写操作写审计，审计内容只包含编码、名称与掩码，不包含密钥明文。
 */
@Service
public class AiGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(AiGovernanceService.class);

    private final AiGovernanceRepository repository;
    private final AiSecretCipher cipher;
    private final AiAccessPolicy policy;
    private final AiProviderProbeService probeService;
    private final AiCallLogService callLogService;
    private final AuditLogService audit;

    public AiGovernanceService(AiGovernanceRepository repository, AiSecretCipher cipher,
                               AiAccessPolicy policy, AiProviderProbeService probeService,
                               AiCallLogService callLogService, AuditLogService audit) {
        this.repository = repository;
        this.cipher = cipher;
        this.policy = policy;
        this.probeService = probeService;
        this.callLogService = callLogService;
        this.audit = audit;
    }

    /* ---------------- 查询 ---------------- */

    public List<Provider> listProviders() {
        policy.requireAdmin();
        return repository.findProviders();
    }

    public List<Model> listModels() {
        policy.requireAdmin();
        return repository.findModels();
    }

    public List<ScenarioRoute> listRoutes() {
        policy.requireAdmin();
        return repository.findRoutes();
    }

    public List<Quota> listQuotas() {
        policy.requireAdmin();
        return repository.findQuotas();
    }

    public CallStats stats(int days) {
        policy.requireAdmin();
        return callLogService.stats(days);
    }

    public List<CallLogView> recentCalls(int limit) {
        policy.requireAdmin();
        return callLogService.recent(limit);
    }

    public boolean keyConfigured() {
        return cipher.isAvailable();
    }

    /* ---------------- 供应商 ---------------- */

    @Transactional
    public Provider createProvider(ProviderCreate req, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        requireCipher();
        String code = req.code().trim().toLowerCase();
        if (repository.existsProviderCode(code, null)) {
            throw new ConflictException("供应商编码已存在：" + code);
        }
        String apiKey = req.apiKey().trim();
        String cipherText = cipher.encrypt(apiKey);
        String mask = AiSecretCipher.mask(apiKey);
        String baseUrl = normalizeBaseUrl(req.baseUrl());
        Long id = repository.insertProvider(code, req.displayName().trim(), baseUrl, cipherText, mask,
                Boolean.TRUE.equals(req.enabled()), policy.current().id());
        audit("AI_PROVIDER_CREATE", "AI_PROVIDER", id,
                "code=" + code + " baseUrl=" + baseUrl + " key=" + mask + " enabled=" + Boolean.TRUE.equals(req.enabled()),
                meta);
        return requireProvider(id);
    }

    @Transactional
    public Provider updateProvider(Long id, ProviderUpdate req, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        Provider existing = requireProvider(id);
        String baseUrl = normalizeBaseUrl(req.baseUrl());
        repository.updateProvider(id, req.displayName().trim(), baseUrl, policy.current().id());
        audit("AI_PROVIDER_UPDATE", "AI_PROVIDER", id,
                "code=" + existing.code() + " baseUrl=" + baseUrl, meta);
        return requireProvider(id);
    }

    @Transactional
    public Provider updateProviderKey(Long id, ProviderKeyUpdate req, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        requireCipher();
        Provider existing = requireProvider(id);
        String apiKey = req.apiKey().trim();
        String mask = AiSecretCipher.mask(apiKey);
        repository.updateProviderKey(id, cipher.encrypt(apiKey), mask, policy.current().id());
        audit("AI_PROVIDER_KEY_UPDATE", "AI_PROVIDER", id, "code=" + existing.code() + " key=" + mask, meta);
        return requireProvider(id);
    }

    @Transactional
    public Provider setProviderStatus(Long id, boolean enabled, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        Provider existing = requireProvider(id);
        if (enabled) {
            requireCipher();
            if (!existing.hasKey()) {
                throw new ConflictException("请先为该供应商配置 API Key");
            }
        }
        repository.updateProviderStatus(id, enabled, policy.current().id());
        audit(enabled ? "AI_PROVIDER_ENABLE" : "AI_PROVIDER_DISABLE", "AI_PROVIDER", id,
                "code=" + existing.code(), meta);
        return requireProvider(id);
    }

    @Transactional
    public TestResult testProvider(Long id, String modelCode, AiRequestMeta meta) {
        policy.requireAdmin();
        ProviderSecret provider = repository.findProviderSecret(id)
                .orElseThrow(() -> new NotFoundException("供应商不存在：id=" + id));
        if (provider.apiKeyCipher() == null) {
            throw new ConflictException("该供应商尚未配置 API Key");
        }
        requireCipher();
        String apiKey;
        try {
            apiKey = cipher.decrypt(provider.apiKeyCipher());
        } catch (RuntimeException e) {
            throw new ConflictException("供应商密钥无法解密，请重新设置 API Key");
        }
        String model = modelCode == null || modelCode.isBlank()
                ? repository.findModels().stream()
                        .filter(item -> item.providerId().equals(id))
                        .map(Model::modelCode).findFirst().orElse("deepseek-chat")
                : modelCode.trim();
        TestResult result = probeService.probe(provider.baseUrl(), apiKey, model);
        repository.updateProviderTest(id, result.success(), result.message());
        if (result.success()) {
            repository.findModels().stream()
                    .filter(item -> item.providerId().equals(id) && item.modelCode().equals(model))
                    .findFirst()
                    .ifPresent(item -> repository.markModelVerified(item.id(), true, policy.current().id()));
        }
        audit(result.success() ? "AI_PROVIDER_TEST_SUCCESS" : "AI_PROVIDER_TEST_FAILURE",
                "AI_PROVIDER", id, "code=" + provider.code() + " model=" + model + " result=" + result.message(), meta);
        return result;
    }

    /* ---------------- 模型 ---------------- */

    @Transactional
    public Model createModel(ModelCreate req, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        Provider provider = requireProvider(req.providerId());
        String modelCode = req.modelCode().trim();
        if (repository.existsModelCode(provider.id(), modelCode, null)) {
            throw new ConflictException("该供应商下已存在模型：" + modelCode);
        }
        Long id = repository.insertModel(provider.id(), modelCode, req.displayName().trim(), false,
                req.temperature() == null ? 0.2d : req.temperature(),
                req.maxTokens() == null ? 2048 : req.maxTokens(),
                req.timeoutSeconds() == null ? 60 : req.timeoutSeconds(),
                req.costPer1kMicros() == null ? 0 : req.costPer1kMicros(),
                policy.current().id());
        audit("AI_MODEL_CREATE", "AI_MODEL", id, "provider=" + provider.code() + " model=" + modelCode, meta);
        return requireModel(id);
    }

    @Transactional
    public Model updateModel(Long id, ModelUpdate req, AiRequestMeta meta) {
        policy.requireAdmin();
        Model existing = requireModel(id);
        repository.updateModel(id, req.displayName().trim(),
                req.temperature() == null ? existing.temperature() : req.temperature(),
                req.maxTokens() == null ? existing.maxTokens() : req.maxTokens(),
                req.timeoutSeconds() == null ? existing.timeoutSeconds() : req.timeoutSeconds(),
                req.costPer1kMicros() == null ? existing.costPer1kMicros() : req.costPer1kMicros(),
                policy.current().id());
        audit("AI_MODEL_UPDATE", "AI_MODEL", id, "model=" + existing.modelCode(), meta);
        return requireModel(id);
    }

    @Transactional
    public Model setModelStatus(Long id, boolean enabled, AiRequestMeta meta) {
        policy.requireAdmin();
        Model existing = requireModel(id);
        if (enabled && !existing.verified()) {
            throw new ConflictException("模型尚未通过连接测试，不能启用");
        }
        repository.updateModelStatus(id, enabled, policy.current().id());
        if (!enabled && existing.isDefault()) {
            repository.clearDefaultModels(policy.current().id());
        }
        audit(enabled ? "AI_MODEL_ENABLE" : "AI_MODEL_DISABLE", "AI_MODEL", id,
                "model=" + existing.modelCode(), meta);
        return requireModel(id);
    }

    @Transactional
    public Model verifyModel(Long id, AiRequestMeta meta) {
        policy.requireAdmin();
        Model model = requireModel(id);
        ProviderSecret provider = repository.findProviderSecret(model.providerId())
                .orElseThrow(() -> new NotFoundException("供应商不存在：id=" + model.providerId()));
        if (provider.apiKeyCipher() == null) {
            throw new ConflictException("该供应商尚未配置 API Key");
        }
        requireCipher();
        String apiKey;
        try {
            apiKey = cipher.decrypt(provider.apiKeyCipher());
        } catch (RuntimeException e) {
            throw new ConflictException("供应商密钥无法解密，请重新设置 API Key");
        }
        TestResult result = probeService.probe(provider.baseUrl(), apiKey, model.modelCode());
        if (result.success()) {
            repository.markModelVerified(id, true, policy.current().id());
        }
        audit(result.success() ? "AI_MODEL_VERIFY_SUCCESS" : "AI_MODEL_VERIFY_FAILURE", "AI_MODEL", id,
                "model=" + model.modelCode() + " result=" + result.message(), meta);
        if (!result.success()) {
            throw new ConflictException("连接测试失败：" + result.message());
        }
        return requireModel(id);
    }

    @Transactional
    public Model setDefaultModel(Long id, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        Model existing = requireModel(id);
        if (!existing.enabled() || !existing.verified()) {
            throw new ConflictException("只有已启用且已验证的模型才能设为默认模型");
        }
        repository.clearDefaultModels(policy.current().id());
        repository.markDefaultModel(id, policy.current().id());
        audit("AI_MODEL_SET_DEFAULT", "AI_MODEL", id, "model=" + existing.modelCode(), meta);
        return requireModel(id);
    }

    /* ---------------- 场景路由 ---------------- */

    @Transactional
    public ScenarioRoute updateRoute(String scenario, RouteUpdate req, AiRequestMeta meta) {
        policy.requireAdmin();
        String key = normalizeScenario(scenario);
        Model primary = req.primaryModelId() == null ? repository.findDefaultModel().orElse(null)
                : requireModel(req.primaryModelId());
        Model fallback = req.fallbackModelId() == null ? null : requireModel(req.fallbackModelId());
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        if (enabled) {
            if (primary == null) {
                throw new ConflictException("启用路由前必须指定主模型或设置全局默认模型");
            }
            requireUsable(primary, "主模型");
            if (fallback != null) {
                requireUsable(fallback, "备用模型");
            }
        }
        if (primary != null && fallback != null && primary.id().equals(fallback.id())) {
            throw new IllegalArgumentException("主模型与备用模型不能相同");
        }
        repository.upsertRoute(key, primary == null ? null : primary.id(),
                fallback == null ? null : fallback.id(),
                req.temperature(), req.maxTokens(), req.timeoutSeconds(),
                req.maxRetries() == null ? 1 : req.maxRetries(), enabled, policy.current().id());
        audit("AI_ROUTE_UPDATE", "AI_ROUTE", key,
                "primary=" + (primary == null ? "none" : primary.modelCode())
                        + " fallback=" + (fallback == null ? "none" : fallback.modelCode())
                        + " enabled=" + enabled, meta);
        return repository.findRoute(key).orElseThrow(() -> new NotFoundException("路由不存在：" + key));
    }

    /* ---------------- 配额 ---------------- */

    @Transactional
    public Quota upsertQuota(QuotaUpdate req, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        String scopeType = req.scopeType().trim().toUpperCase();
        String rawScopeValue = req.scopeValue() == null ? "" : req.scopeValue().trim();
        if (!"GLOBAL".equals(scopeType) && rawScopeValue.isEmpty()) {
            throw new IllegalArgumentException("非全局配额必须填写作用范围");
        }
        if ("USER".equals(scopeType) && !rawScopeValue.matches("\\d+")) {
            throw new IllegalArgumentException("用户配额的作用范围必须是用户 ID");
        }
        if ("ROLE".equals(scopeType) && !List.of("ADMIN", "AGENT", "USER").contains(rawScopeValue.toUpperCase())) {
            throw new IllegalArgumentException("角色配额的作用范围必须是 ADMIN / AGENT / USER");
        }
        final String scopeValue = "SCENARIO".equals(scopeType) ? normalizeScenario(rawScopeValue)
                : ("ROLE".equals(scopeType) ? rawScopeValue.toUpperCase()
                : ("GLOBAL".equals(scopeType) ? "" : rawScopeValue));
        final String period = req.period().trim().toUpperCase();
        repository.upsertQuota(scopeType, scopeValue, period, req.maxCalls(),
                req.enabled() == null || req.enabled(), policy.current().id());
        audit("AI_QUOTA_UPDATE", "AI_QUOTA", scopeType + ":" + scopeValue,
                "period=" + period + " maxCalls=" + req.maxCalls(), meta);
        return repository.findQuotas().stream()
                .filter(item -> item.scopeType().equals(scopeType)
                        && item.scopeValue().equals(scopeValue)
                        && item.period().equals(period))
                .findFirst()
                .orElseThrow(() -> new ConflictException("配额保存失败，请重试"));
    }

    @Transactional
    public void deleteQuota(Long id, AiRequestMeta meta) {
        policy.requireSuperAdmin();
        Quota quota = repository.findQuota(id)
                .orElseThrow(() -> new NotFoundException("配额不存在：id=" + id));
        repository.deleteQuota(id);
        audit("AI_QUOTA_DELETE", "AI_QUOTA", String.valueOf(id),
                "scope=" + quota.scopeType() + ":" + quota.scopeValue(), meta);
    }

    /* ---------------- 内部 ---------------- */

    private void requireCipher() {
        if (!cipher.isAvailable()) {
            throw new ConflictException("服务端未配置 AI_SECRET_KEY，暂时无法保存或使用供应商密钥");
        }
    }

    private Provider requireProvider(Long id) {
        return repository.findProvider(id)
                .orElseThrow(() -> new NotFoundException("供应商不存在：id=" + id));
    }

    private Model requireModel(Long id) {
        return repository.findModel(id)
                .orElseThrow(() -> new NotFoundException("模型不存在：id=" + id));
    }

    private void requireUsable(Model model, String label) {
        if (!model.enabled() || !model.verified()) {
            throw new ConflictException(label + " " + model.modelCode() + " 尚未启用或未通过连接测试");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String normalizeScenario(String scenario) {
        String value = scenario == null ? "" : scenario.trim().toUpperCase();
        if (!List.of("TICKET_ANALYSIS", "PRIORITY", "SOLUTION", "KNOWLEDGE", "DISPATCH", "CHAT")
                .contains(value)) {
            throw new IllegalArgumentException("不支持的 AI 场景：" + scenario);
        }
        return value;
    }

    private void audit(String action, String objectType, Object objectId, String detail, AiRequestMeta meta) {
        var user = policy.current();
        audit.success(user.id(), user.username(), action, objectType,
                objectId == null ? null : String.valueOf(objectId), detail,
                meta == null ? null : meta.ip(), meta == null ? null : meta.requestId());
    }
}

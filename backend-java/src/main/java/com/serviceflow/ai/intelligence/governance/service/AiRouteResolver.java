package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.intelligence.governance.repository.AiGovernanceRepository;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Model;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ProviderSecret;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ScenarioRoute;
import com.serviceflow.ai.intelligence.governance.security.AiSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 场景路由解析：只有「路由启用 + 供应商启用且有密钥 + 模型已启用且已验证」才返回托管路由。
 * 任何一环不满足都返回空，调用方继续沿用环境变量配置，保证治理配置未就绪时线上行为不变。
 */
@Component
public class AiRouteResolver {

    private static final Logger log = LoggerFactory.getLogger(AiRouteResolver.class);

    private final AiGovernanceRepository repository;
    private final AiSecretCipher cipher;

    public AiRouteResolver(AiGovernanceRepository repository, AiSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public Optional<AiRoutePlan> resolve(String scenario) {
        ScenarioRoute route;
        try {
            route = repository.findRoute(scenario).orElse(null);
        } catch (Exception e) {
            log.warn("ai_route_lookup_failed scenario={} type={}", scenario, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (route == null || !route.enabled()) {
            return Optional.empty();
        }
        Model primary = route.primaryModelId() == null
                ? repository.findDefaultModel().orElse(null)
                : repository.findModel(route.primaryModelId()).orElse(null);
        if (primary == null) {
            return Optional.empty();
        }
        AiRoutePlan primaryPlan = build(primary, route);
        if (primaryPlan == null) {
            return Optional.empty();
        }
        AiRoutePlan fallbackPlan = null;
        if (route.fallbackModelId() != null && !route.fallbackModelId().equals(primary.id())) {
            Model fallback = repository.findModel(route.fallbackModelId()).orElse(null);
            if (fallback != null) {
                fallbackPlan = build(fallback, route);
            }
        }
        return Optional.of(new AiRoutePlan(primaryPlan.providerId(), primaryPlan.modelId(),
                primaryPlan.providerCode(), primaryPlan.baseUrl(), primaryPlan.apiKey(),
                primaryPlan.model(), primaryPlan.temperature(), primaryPlan.maxTokens(),
                primaryPlan.timeoutSeconds(), route.maxRetries(), primaryPlan.costPer1kMicros(),
                fallbackPlan));
    }

    private AiRoutePlan build(Model model, ScenarioRoute route) {
        if (!model.enabled() || !model.verified()) {
            return null;
        }
        ProviderSecret provider = repository.findProviderSecret(model.providerId()).orElse(null);
        if (provider == null || !provider.enabled() || provider.apiKeyCipher() == null) {
            return null;
        }
        if (!cipher.isAvailable()) {
            log.warn("ai_route_skipped reason=AI_SECRET_KEY_MISSING providerId={}", provider.id());
            return null;
        }
        String apiKey;
        try {
            apiKey = cipher.decrypt(provider.apiKeyCipher());
        } catch (RuntimeException e) {
            log.warn("ai_route_skipped reason=KEY_DECRYPT_FAILED providerId={}", provider.id());
            return null;
        }
        double temperature = route.temperature() == null ? model.temperature() : route.temperature();
        int maxTokens = route.maxTokens() == null ? model.maxTokens() : route.maxTokens();
        int timeout = route.timeoutSeconds() == null ? model.timeoutSeconds() : route.timeoutSeconds();
        return new AiRoutePlan(provider.id(), model.id(), provider.code(), provider.baseUrl(), apiKey,
                model.modelCode(), temperature, maxTokens, timeout,
                Math.max(0, Math.min(3, route.maxRetries())), model.costPer1kMicros(), null);
    }
}

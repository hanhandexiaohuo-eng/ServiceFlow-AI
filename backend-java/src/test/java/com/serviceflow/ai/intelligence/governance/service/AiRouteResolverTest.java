package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Model;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ProviderSecret;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ScenarioRoute;
import com.serviceflow.ai.intelligence.governance.repository.AiGovernanceRepository;
import com.serviceflow.ai.intelligence.governance.security.AiSecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRouteResolverTest {

    @Mock
    private AiGovernanceRepository repository;

    private AiSecretCipher cipher;
    private AiRouteResolver resolver;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        cipher = new AiSecretCipher(key);
        resolver = new AiRouteResolver(repository, cipher);
    }

    private Model model(long id, boolean enabled, boolean verified) {
        return new Model(id, 10L, "deepseek", "deepseek-chat", "DeepSeek Chat",
                enabled, verified, false, 0.2, 2048, 60, 1000L, null, null);
    }

    private ProviderSecret provider(String cipherText, boolean enabled) {
        return new ProviderSecret(10L, "deepseek", "DeepSeek", "https://api.deepseek.com",
                cipherText, enabled);
    }

    @Test
    void 路由关闭时返回空并沿用环境变量配置() {
        when(repository.findRoute("CHAT")).thenReturn(Optional.of(
                new ScenarioRoute(1L, "CHAT", 1L, null, null, null, null, 1, false, null)));

        assertThat(resolver.resolve("CHAT")).isEmpty();
    }

    @Test
    void 主备模型都可用时返回降级计划() {
        when(repository.findRoute("CHAT")).thenReturn(Optional.of(
                new ScenarioRoute(1L, "CHAT", 1L, 2L, 0.5, 1024, 30, 2, true, null)));
        when(repository.findModel(1L)).thenReturn(Optional.of(model(1L, true, true)));
        when(repository.findModel(2L)).thenReturn(Optional.of(model(2L, true, true)));
        when(repository.findProviderSecret(10L)).thenReturn(Optional.of(provider(cipher.encrypt("sk-test-key"), true)));

        AiRoutePlan plan = resolver.resolve("CHAT").orElseThrow();

        assertThat(plan.model()).isEqualTo("deepseek-chat");
        assertThat(plan.apiKey()).isEqualTo("sk-test-key");
        assertThat(plan.temperature()).isEqualTo(0.5);
        assertThat(plan.maxTokens()).isEqualTo(1024);
        assertThat(plan.timeoutSeconds()).isEqualTo(30);
        assertThat(plan.maxRetries()).isEqualTo(2);
        assertThat(plan.fallback()).isNotNull();
    }

    @Test
    void 模型未验证时不使用托管路由() {
        when(repository.findRoute("CHAT")).thenReturn(Optional.of(
                new ScenarioRoute(1L, "CHAT", 1L, null, null, null, null, 1, true, null)));
        when(repository.findModel(1L)).thenReturn(Optional.of(model(1L, true, false)));

        assertThat(resolver.resolve("CHAT")).isEmpty();
    }

    @Test
    void 供应商停用时回退到环境变量配置() {
        when(repository.findRoute("CHAT")).thenReturn(Optional.of(
                new ScenarioRoute(1L, "CHAT", 1L, null, null, null, null, 1, true, null)));
        when(repository.findModel(1L)).thenReturn(Optional.of(model(1L, true, true)));
        when(repository.findProviderSecret(10L))
                .thenReturn(Optional.of(provider(cipher.encrypt("sk-test-key"), false)));

        assertThat(resolver.resolve("CHAT")).isEmpty();
    }

    @Test
    void 未配置主密钥时不使用托管路由() {
        AiRouteResolver withoutKey = new AiRouteResolver(repository, new AiSecretCipher(""));
        when(repository.findRoute("CHAT")).thenReturn(Optional.of(
                new ScenarioRoute(1L, "CHAT", 1L, null, null, null, null, 1, true, null)));
        when(repository.findModel(1L)).thenReturn(Optional.of(model(1L, true, true)));
        when(repository.findProviderSecret(10L))
                .thenReturn(Optional.of(provider("cipher-text", true)));

        assertThat(withoutKey.resolve("CHAT")).isEmpty();
    }
}

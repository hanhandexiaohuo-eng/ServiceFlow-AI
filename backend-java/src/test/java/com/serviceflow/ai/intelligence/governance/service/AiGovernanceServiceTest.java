package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.ProviderCreate;
import com.serviceflow.ai.intelligence.governance.dto.AiGovernanceDtos.RouteUpdate;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Model;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Provider;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.ProviderSecret;
import com.serviceflow.ai.intelligence.governance.repository.AiGovernanceRepository;
import com.serviceflow.ai.intelligence.governance.security.AiAccessPolicy;
import com.serviceflow.ai.intelligence.governance.security.AiSecretCipher;
import com.serviceflow.ai.intelligence.governance.vo.AiGovernanceResponses.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGovernanceServiceTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Mock
    private AiGovernanceRepository repository;
    @Mock
    private AiProviderProbeService probeService;
    @Mock
    private AiCallLogService callLogService;
    @Mock
    private AuditLogService auditLogService;

    private AiSecretCipher cipher;
    private AiGovernanceService service;

    @BeforeEach
    void setUp() {
        cipher = new AiSecretCipher(KEY);
        service = new AiGovernanceService(repository, cipher, new AiAccessPolicy(""),
                probeService, callLogService, auditLogService);
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void loginAs(UserRole role, String username) {
        UserContext.set(new LoginUser(1L, username, username, role));
    }

    private Provider providerRow(boolean hasKey) {
        return new Provider(7L, "deepseek", "DeepSeek 官方", "https://api.deepseek.com",
                "sk-****-123", hasKey, true, null, null, null, null, null);
    }

    private Model model(long id, Long providerId, String code, boolean enabled, boolean verified) {
        return new Model(id, providerId, "deepseek", code, code, enabled, verified, false,
                0.2, 2048, 60, 1000L, null, null);
    }

    @Test
    void 非管理员不能访问治理配置() {
        loginAs(UserRole.AGENT, "agent");

        assertThatThrownBy(() -> service.listProviders())
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).findProviders();
    }

    @Test
    void 普通管理员不能创建供应商或修改密钥() {
        loginAs(UserRole.ADMIN, "operator");
        AiGovernanceService strict = new AiGovernanceService(repository, cipher,
                new AiAccessPolicy("admin"), probeService, callLogService, auditLogService);

        assertThatThrownBy(() -> strict.createProvider(
                new ProviderCreate("deepseek", "DeepSeek", "https://api.deepseek.com", "sk-raw-key-123", true),
                new AiRequestMeta("127.0.0.1", "r1")))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).insertProvider(anyString(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void 创建供应商只保存密文且审计不含明文密钥() {
        loginAs(UserRole.ADMIN, "admin");
        when(repository.existsProviderCode("deepseek", null)).thenReturn(false);
        when(repository.insertProvider(anyString(), anyString(), anyString(), anyString(), anyString(),
                eq(true), eq(1L))).thenReturn(7L);
        when(repository.findProvider(7L)).thenReturn(Optional.of(providerRow(true)));

        Provider created = service.createProvider(new ProviderCreate(
                "DeepSeek", "DeepSeek 官方", "https://api.deepseek.com/", "sk-raw-key-123", true),
                new AiRequestMeta("127.0.0.1", "r1"));

        assertThat(created.id()).isEqualTo(7L);
        ArgumentCaptor<String> cipherCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> maskCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insertProvider(eq("deepseek"), eq("DeepSeek 官方"),
                eq("https://api.deepseek.com"), cipherCaptor.capture(), maskCaptor.capture(), eq(true), eq(1L));
        assertThat(cipherCaptor.getValue()).doesNotContain("sk-raw-key-123");
        assertThat(cipher.decrypt(cipherCaptor.getValue())).isEqualTo("sk-raw-key-123");
        assertThat(maskCaptor.getValue()).isEqualTo("sk-****-123");

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).success(eq(1L), eq("admin"), eq("AI_PROVIDER_CREATE"),
                eq("AI_PROVIDER"), eq("7"), detailCaptor.capture(), eq("127.0.0.1"), eq("r1"));
        assertThat(detailCaptor.getValue()).doesNotContain("sk-raw-key-123").contains("sk-****-123");
    }

    @Test
    void 未配置密钥的供应商不能启用() {
        loginAs(UserRole.ADMIN, "admin");
        when(repository.findProvider(7L)).thenReturn(Optional.of(providerRow(false)));

        assertThatThrownBy(() -> service.setProviderStatus(7L, true, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void 未验证的模型不能启用() {
        loginAs(UserRole.ADMIN, "admin");
        when(repository.findModel(5L)).thenReturn(Optional.of(model(5L, 7L, "deepseek-chat", true, false)));

        assertThatThrownBy(() -> service.setModelStatus(5L, true, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("连接测试");
    }

    @Test
    void 启用路由要求主备模型均已启用并验证() {
        loginAs(UserRole.ADMIN, "admin");
        when(repository.findModel(5L)).thenReturn(Optional.of(model(5L, 7L, "deepseek-chat", true, false)));

        assertThatThrownBy(() -> service.updateRoute("CHAT",
                new RouteUpdate(5L, null, null, null, null, 1, true), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 连接测试成功后自动标记模型已验证() {
        loginAs(UserRole.ADMIN, "admin");
        when(repository.findProviderSecret(10L)).thenReturn(Optional.of(
                new ProviderSecret(10L, "deepseek", "DeepSeek", "https://api.deepseek.com",
                        cipher.encrypt("sk-test-key"), true)));
        when(repository.findModels()).thenReturn(List.of(model(5L, 10L, "deepseek-chat", true, false)));
        when(probeService.probe("https://api.deepseek.com", "sk-test-key", "deepseek-chat"))
                .thenReturn(new TestResult(true, "连接成功", 12));

        TestResult result = service.testProvider(10L, "deepseek-chat", new AiRequestMeta("127.0.0.1", "r2"));

        assertThat(result.success()).isTrue();
        verify(repository).updateProviderTest(10L, true, "连接成功");
        verify(repository).markModelVerified(5L, true, 1L);
    }

    @Test
    void 默认模型必须是已启用且已验证的模型() {
        loginAs(UserRole.ADMIN, "admin");
        when(repository.findModel(5L)).thenReturn(Optional.of(model(5L, 7L, "deepseek-chat", true, false)));

        assertThatThrownBy(() -> service.setDefaultModel(5L, null))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).markDefaultModel(any(), any());
    }
}

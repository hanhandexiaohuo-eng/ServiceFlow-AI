package com.serviceflow.ai.intelligence.governance.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSecretCipherTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void 加解密可逆且密文每次不同() {
        AiSecretCipher cipher = new AiSecretCipher(KEY);
        String plain = "sk-live-abcdefghijklmnop";
        String first = cipher.encrypt(plain);
        String second = cipher.encrypt(plain);

        assertThat(cipher.isAvailable()).isTrue();
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(plain);
        assertThat(first).doesNotContain(plain);
    }

    @Test
    void 掩码不暴露完整密钥() {
        String masked = AiSecretCipher.mask("sk-live-abcdefghijklmnop");
        assertThat(masked).isEqualTo("sk-****mnop");
        assertThat(AiSecretCipher.mask("short")).isEqualTo("*****");
        assertThat(AiSecretCipher.mask("")).isEmpty();
    }

    @Test
    void 未配置主密钥时拒绝加密() {
        AiSecretCipher cipher = new AiSecretCipher("");
        assertThat(cipher.isAvailable()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 非法主密钥长度不启用加密() {
        String tooShort = Base64.getEncoder().encodeToString("1234567890".getBytes(StandardCharsets.UTF_8));
        assertThat(new AiSecretCipher(tooShort).isAvailable()).isFalse();
    }
}

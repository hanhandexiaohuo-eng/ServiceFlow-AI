package com.serviceflow.ai.intelligence.governance.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 供应商密钥加密：AES-256-GCM，主密钥只来自部署环境变量 AI_SECRET_KEY。
 * 未配置主密钥时拒绝写入密钥，避免以明文或弱加密方式落库。
 */
@Component
public class AiSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(AiSecretCipher.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AiSecretCipher(@Value("${ai.governance.secret-key:}") String base64Key) {
        SecretKey parsed = null;
        if (base64Key != null && !base64Key.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(base64Key.trim());
                if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
                    throw new IllegalArgumentException("key length must be 16/24/32 bytes");
                }
                parsed = new SecretKeySpec(raw, "AES");
            } catch (RuntimeException e) {
                log.error("AI_SECRET_KEY 格式不合法：需要 base64 编码的 32 字节密钥，密钥管理功能不可用");
            }
        }
        this.key = parsed;
    }

    public boolean isAvailable() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (key == null) {
            throw new IllegalStateException("AI_SECRET_KEY 未配置，无法保存供应商密钥");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("供应商密钥加密失败", e);
        }
    }

    public String decrypt(String payload) {
        if (key == null) {
            throw new IllegalStateException("AI_SECRET_KEY 未配置，无法读取供应商密钥");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("供应商密钥解密失败", e);
        }
    }

    /** 掩码展示：任何情况下都不返回完整密钥。 */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        String value = plain.trim();
        if (value.length() <= 8) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }
}

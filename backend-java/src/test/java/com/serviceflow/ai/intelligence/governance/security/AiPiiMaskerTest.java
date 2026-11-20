package com.serviceflow.ai.intelligence.governance.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiPiiMaskerTest {

    private final AiPiiMasker masker = new AiPiiMasker();

    @Test
    void 手机号邮箱身份证银行卡全部脱敏() {
        String text = "联系人 13812345678，邮箱 alice@example.com，身份证 110101199001011234，卡号 6222021234567890123";
        String masked = masker.mask(text);

        assertThat(masked).doesNotContain("13812345678")
                .doesNotContain("alice@example.com")
                .doesNotContain("110101199001011234")
                .doesNotContain("6222021234567890123")
                .contains("[PHONE]").contains("[EMAIL]").contains("[ID_CARD]").contains("[CARD]");
    }

    @Test
    void 密钥与Token形态被替换() {
        String masked = masker.mask("Authorization: Bearer abcdefghijklmnop12345, key sk-abcdefghijklmnop");
        assertThat(masked).doesNotContain("abcdefghijklmnop12345")
                .doesNotContain("sk-abcdefghijklmnop")
                .contains("[TOKEN]").contains("[API_KEY]");
    }

    @Test
    void 递归脱敏保留结构与工单必填字段() {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("title", "无法登录 13812345678");
        ticket.put("description", "邮箱 bob@example.com 收不到验证码");
        ticket.put("tags", List.of("vip", "13800001111"));
        Map<String, Object> payload = Map.of("ticket", ticket, "count", 3);

        Map<String, Object> masked = masker.maskPayload(payload);

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedTicket = (Map<String, Object>) masked.get("ticket");
        assertThat(maskedTicket.get("title")).isEqualTo("无法登录 [PHONE]");
        assertThat(maskedTicket.get("description")).isEqualTo("邮箱 [EMAIL] 收不到验证码");
        assertThat(maskedTicket.get("tags")).isEqualTo(List.of("vip", "[PHONE]"));
        assertThat(masked.get("count")).isEqualTo(3);
    }

    @Test
    void 普通文本保持不变() {
        String text = "服务无法启动，错误码 E1024";
        assertThat(masker.mask(text)).isEqualTo(text);
    }
}

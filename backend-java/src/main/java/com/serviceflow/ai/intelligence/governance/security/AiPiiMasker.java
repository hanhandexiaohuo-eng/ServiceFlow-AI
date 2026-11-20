package com.serviceflow.ai.intelligence.governance.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 发送给模型前的敏感信息脱敏：
 * 手机号、邮箱、身份证号、银行卡号、常见密钥/Token 形态统一替换为占位符，
 * 保证模型侧与调用日志中都不出现可识别的客户敏感信息。
 */
@Component
public class AiPiiMasker {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{12,}");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\b(sk|ak|pk)-[A-Za-z0-9_\\-]{12,}\\b");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}");

    public Map<String, Object> maskPayload(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> result.put(key, maskDeep(value)));
        return result;
    }

    /** 递归脱敏 Map/List/String；其他类型原样返回。 */
    public Object maskDeep(Object value) {
        if (value instanceof String text) {
            return mask(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, item) -> copied.put(String.valueOf(key), maskDeep(item)));
            return copied;
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(item -> copied.add(maskDeep(item)));
            return copied;
        }
        return value;
    }

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String value = JWT.matcher(text).replaceAll("[TOKEN]");
        value = BEARER.matcher(value).replaceAll("Bearer [TOKEN]");
        value = API_KEY.matcher(value).replaceAll("[API_KEY]");
        value = EMAIL.matcher(value).replaceAll("[EMAIL]");
        value = ID_CARD.matcher(value).replaceAll("[ID_CARD]");
        value = MOBILE.matcher(value).replaceAll("[PHONE]");
        value = BANK_CARD.matcher(value).replaceAll("[CARD]");
        return value;
    }
}

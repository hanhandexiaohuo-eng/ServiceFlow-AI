package com.serviceflow.ai.intelligence.governance.security;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 治理权限：
 * - 超级管理员（ADMIN 且命中 security.super-admin-usernames，未配置时所有 ADMIN 视为超级管理员）
 *   可新增/修改供应商与密钥、创建模型、设默认模型；
 * - 普通 ADMIN 只能调整场景路由参数、启停已批准（已验证）模型。
 */
@Component
public class AiAccessPolicy {

    private final Set<String> superAdmins;

    public AiAccessPolicy(@Value("${security.super-admin-usernames:}") String configured) {
        this.superAdmins = Arrays.stream(configured.split(","))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public LoginUser current() {
        return UserContext.getRequired();
    }

    public void requireAdmin() {
        if (current().role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可访问 AI 治理配置");
        }
    }

    public void requireSuperAdmin() {
        requireAdmin();
        if (!isSuperAdmin()) {
            throw new ForbiddenException("仅超级管理员可修改供应商与模型密钥");
        }
    }

    public boolean isSuperAdmin() {
        LoginUser user = current();
        if (user.role() != UserRole.ADMIN) {
            return false;
        }
        if (superAdmins.isEmpty()) {
            return true;
        }
        return superAdmins.contains(user.username().toLowerCase(Locale.ROOT));
    }
}

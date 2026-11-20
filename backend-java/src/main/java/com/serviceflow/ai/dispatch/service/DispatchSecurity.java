package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.springframework.stereotype.Component;

/** 调度模块的权限边界：管理员可管理，处理人员仅可读与调整本人状态。 */
@Component
public class DispatchSecurity {

    public LoginUser current() {
        return UserContext.getRequired();
    }

    public void requireAdmin() {
        if (current().role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可执行该操作");
        }
    }

    public void requireAdminOrAgent() {
        UserRole role = current().role();
        if (role != UserRole.ADMIN && role != UserRole.AGENT) {
            throw new ForbiddenException("仅管理员或处理人员可查看该数据");
        }
    }
}

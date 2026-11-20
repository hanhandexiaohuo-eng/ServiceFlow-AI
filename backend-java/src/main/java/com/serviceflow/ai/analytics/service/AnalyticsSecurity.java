package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.springframework.stereotype.Service;

/** 运营分析的统一权限入口：只有管理员与处理人员能看到全局统计。 */
@Service
public class AnalyticsSecurity {

    /**
     * 校验当前用户可查看分析数据。UserContext 为空属于拦截器漏配（编程错误），
     * 由 getRequired 抛 500，不会被这里静默放行。
     */
    public void requireViewer() {
        LoginUser user = UserContext.getRequired();
        UserRole role = user.role();
        if (role != UserRole.ADMIN && role != UserRole.AGENT) {
            throw new ForbiddenException("仅管理员或处理人员可查看运营分析");
        }
    }
}

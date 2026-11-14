package com.serviceflow.ai.common.exception;

/** 403：已登录但权限不足（如普通用户删除工单）。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

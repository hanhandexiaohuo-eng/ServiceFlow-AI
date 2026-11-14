package com.serviceflow.ai.common.exception;

/** 401：未登录、登录失效或账密错误。由全局异常处理器转成统一响应。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}

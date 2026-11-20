package com.serviceflow.ai.common.exception;

/** 请求过于频繁（登录限流、账号临时锁定）→ 429 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}

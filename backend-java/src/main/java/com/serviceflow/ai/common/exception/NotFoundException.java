package com.serviceflow.ai.common.exception;

/**
 * 资源不存在（如查询/更新/删除一个不存在的工单）。
 * 由 GlobalExceptionHandler 统一转成 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

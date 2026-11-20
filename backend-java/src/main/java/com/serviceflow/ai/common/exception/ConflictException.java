package com.serviceflow.ai.common.exception;

/** 并发冲突：工单已被其他人流转，客户端应刷新后重试 → 409。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

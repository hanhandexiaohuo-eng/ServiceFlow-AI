package com.serviceflow.ai.common.dto;

/**
 * 统一响应体：所有业务接口都返回 {code, message, data} 三段结构，
 * 前端只需判断 code，不用解析各种形状的错误。
 * code 与 HTTP 状态码保持一致（200/201/400/404/500）。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "created", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}

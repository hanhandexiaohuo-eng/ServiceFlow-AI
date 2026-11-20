package com.serviceflow.ai.intelligence.client;

/** AI 调用异常：携带对外错误码与 HTTP 状态，不包含模型原始输出。 */
public class AiServiceException extends RuntimeException {

    private final String code;
    private final int statusCode;

    public AiServiceException(String code, String message, int statusCode) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }
}

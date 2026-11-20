package com.serviceflow.ai.auth.web;

/**
 * 会话 Cookie 常量。会话令牌只放 HttpOnly Cookie，前端脚本不可读取；
 * CSRF Cookie 需要被前端读取后回填请求头，因此不是 HttpOnly，但本身不是凭据。
 */
public final class AuthCookies {

    /** 会话令牌（HttpOnly） */
    public static final String SESSION = "sf_session";
    /** CSRF 双提交令牌（可读，需与请求头一致） */
    public static final String CSRF = "sf_csrf";
    /** 写操作必须携带的 CSRF 请求头 */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private AuthCookies() {
    }
}

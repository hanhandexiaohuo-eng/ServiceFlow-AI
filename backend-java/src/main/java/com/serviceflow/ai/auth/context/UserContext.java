package com.serviceflow.ai.auth.context;

/**
 * 用户上下文：ThreadLocal 保存当前请求线程的登录用户。
 *
 * 为什么必须 remove：Tomcat 用线程池复用线程，请求结束后线程会还给池子服务下一个请求。
 * 如果不清除，下一个请求在拦截器 set 之前读到的是上一个用户的信息（串号）。
 * 清理由 AuthInterceptor.afterCompletion 保证，正常和异常情况都会执行。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 未登录时返回 null；业务代码请用 getRequired */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前登录用户，不存在说明拦截器漏配（属于编程错误），抛 500 而不是静默放行。
     */
    public static LoginUser getRequired() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("用户上下文缺失：该接口需要登录，请检查拦截器配置");
        }
        return user;
    }

    public static void remove() {
        HOLDER.remove();
    }
}

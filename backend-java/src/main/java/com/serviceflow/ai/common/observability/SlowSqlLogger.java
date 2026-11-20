package com.serviceflow.ai.common.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 慢 SQL 观测：包住 JdbcTemplate 的公开方法，超过阈值（默认 500ms）输出一条告警日志。
 *
 * - 只记录耗时的 SQL 文本（压缩空白、截断 300 字符），不记录绑定参数，避免日志带出敏感数据。
 * - 参数化 SQL 的占位符保留原样，便于直接复制到 EXPLAIN 分析。
 */
@Aspect
@Component
public class SlowSqlLogger {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlLogger.class);
    private static final int MAX_SQL_LENGTH = 300;

    @Value("${slow-sql.threshold-ms:500}")
    private long thresholdMs = 500;

    @Around("execution(public * org.springframework.jdbc.core.JdbcTemplate.*(..))")
    public Object logSlowSql(ProceedingJoinPoint joinPoint) throws Throwable {
        long started = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long costMs = System.currentTimeMillis() - started;
            if (costMs >= thresholdMs) {
                log.warn("slow_sql costMs={} method={} sql={}",
                        costMs, joinPoint.getSignature().getName(), sqlOf(joinPoint.getArgs()));
            }
        }
    }

    private String sqlOf(Object[] args) {
        String sql = Arrays.stream(args)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElse("-");
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() > MAX_SQL_LENGTH ? compact.substring(0, MAX_SQL_LENGTH) + "..." : compact;
    }
}

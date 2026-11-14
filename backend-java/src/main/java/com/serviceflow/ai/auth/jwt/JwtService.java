package com.serviceflow.ai.auth.jwt;

import com.serviceflow.ai.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析。HS256 对称签名：同一把密钥既签名又验签，密钥绝不能进代码和仓库。
 *
 * 密钥来自环境变量 JWT_SECRET，要求至少 32 字节（HS256 的最低安全强度）。
 * 未配置或太短直接启动失败（fail fast），比静默使用弱默认值安全得多。
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${JWT_SECRET:}") String secret,
                      @Value("${JWT_EXPIRE_MINUTES:120}") long expireMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置或长度不足 " + MIN_SECRET_BYTES + " 字节，应用拒绝启动。"
                            + "请设置高熵随机密钥（如 openssl rand -base64 48）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(expireMinutes);
    }

    /** 签发令牌：sub=userId，附带 username/name/role，2 小时过期。payload 不放任何敏感信息。 */
    public String issueToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("name", user.getName())
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌。签名被篡改、过期、格式非法都会抛 JwtException，
     * 调用方（拦截器）统一转成 401。
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

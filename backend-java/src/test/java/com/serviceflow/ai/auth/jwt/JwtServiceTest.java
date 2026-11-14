package com.serviceflow.ai.auth.jwt;

import com.serviceflow.ai.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 签发与解析的纯单元测试，不依赖 Spring 容器和数据库。 */
class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 正好 32 字节

    private static User sampleUser() {
        User u = new User();
        u.setId(1L);
        u.setUsername("admin");
        u.setName("系统管理员");
        u.setRole("ADMIN");
        u.setStatus(1);
        return u;
    }

    @Test
    void issueAndParseRoundTrip() {
        JwtService service = new JwtService(SECRET, 120);

        String token = service.issueToken(sampleUser());
        Claims claims = service.parse(token);

        assertEquals("1", claims.getSubject());
        assertEquals("admin", claims.get("username"));
        assertEquals("系统管理员", claims.get("name"));
        assertEquals("ADMIN", claims.get("role"));
        assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());
    }

    @Test
    void expiredTokenRejected() {
        // 有效期 -1 分钟：签出来就已经过期
        JwtService service = new JwtService(SECRET, -1);
        String token = service.issueToken(sampleUser());

        assertThrows(JwtException.class, () -> service.parse(token));
    }

    @Test
    void tamperedSignatureRejected() {
        JwtService service = new JwtService(SECRET, 120);
        String token = service.issueToken(sampleUser());
        // 篡改签名段的最后一个字符（换成不同的 base64url 字符）
        char last = token.charAt(token.length() - 1);
        char replacement = last == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertThrows(JwtException.class, () -> service.parse(tampered));
    }

    @Test
    void garbageTokenRejected() {
        JwtService service = new JwtService(SECRET, 120);

        assertThrows(JwtException.class, () -> service.parse("not-a-token"));
    }

    @Test
    void differentSecretCannotVerify() {
        JwtService issuer = new JwtService(SECRET, 120);
        JwtService other = new JwtService("fedcba9876543210fedcba9876543210", 120);
        String token = issuer.issueToken(sampleUser());

        assertThrows(JwtException.class, () -> other.parse(token));
    }

    @Test
    void blankSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> new JwtService("", 120));
    }

    @Test
    void shortSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 120));
    }
}

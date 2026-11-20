package com.serviceflow.ai.auth.jwt;

import com.serviceflow.ai.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 签发与解析的纯单元测试，不依赖 Spring 容器和数据库。 */
class JwtServiceTest {

    /** 每次运行随机生成，源码与仓库中不出现任何固定密钥 */
    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static final String SECRET = randomSecret();

    private static User sampleUser() {
        User u = new User();
        u.setId(1L);
        u.setUsername("admin");
        u.setName("系统管理员");
        u.setRole("ADMIN");
        u.setStatus(1);
        u.setTokenVersion(2);
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
        // 会话版本写入 Token，改密/退出后旧 Token 可被识别并拒绝
        assertEquals(2, claims.get("ver", Integer.class));
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
        // 篡改签名段的中间字符：末位字符的低位可能不参与解码，改末位有概率仍验签通过
        int index = token.length() - 5;
        char original = token.charAt(index);
        char replacement = original == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

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
        JwtService other = new JwtService(randomSecret(), 120);
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

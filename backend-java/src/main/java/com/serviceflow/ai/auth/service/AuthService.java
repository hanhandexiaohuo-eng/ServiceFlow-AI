package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.dto.UserResponse;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 认证业务：登录校验 + 初始管理员种子。
 *
 * 密码安全规范：
 * - 只存 BCrypt 哈希（自带随机盐、慢哈希抗撞库），禁止明文/MD5。
 * - 登录失败不区分"用户不存在"和"密码错误"，防止用户名枚举攻击。
 * - 任何日志都不输出密码或哈希。
 */
@Service
public class AuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt 算法本身的输入上限（字节），超长输入直接视为密码错误，避免触发 encoder 异常 */
    private static final int BCRYPT_MAX_INPUT_BYTES = 72;
    /** 初始密码最少 6 位（本地开发环境取值，生产建议至少 12 位高熵密码） */
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 6;

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String adminInitialPassword;

    public AuthService(UserRepository userRepository,
                       JwtService jwtService,
                       @Value("${ADMIN_INITIAL_PASSWORD:}") String adminInitialPassword) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.adminInitialPassword = adminInitialPassword;
    }

    public LoginResponse login(LoginRequest req) {
        Optional<User> found = userRepository.findByUsername(req.username());
        // 用户不存在与密码错误返回同一提示，防止探测用户名是否存在
        if (found.isEmpty() || !passwordMatches(req.password(), found.get().getPassword())) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        User user = found.get();
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new UnauthorizedException("账号已被禁用");
        }
        log.info("用户登录成功：username={}", user.getUsername());
        return new LoginResponse(jwtService.issueToken(user), UserResponse.from(user));
    }

    /**
     * BCrypt 只处理前 72 字节输入，超长会抛异常。对超长输入直接判定为不匹配，
     * 对外仍表现为统一的"用户名或密码错误"。
     */
    private boolean passwordMatches(String rawPassword, String hash) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_INPUT_BYTES) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }

    /**
     * 初始管理员种子：仅当 users 表为空且 ADMIN_INITIAL_PASSWORD 有效时创建 admin。
     * - 表非空（重复启动）→ 静默跳过
     * - 密码未配置/太弱 → WARN（不含任何敏感信息）
     * - 并发同时初始化 → 唯一索引兜底，DuplicateKeyException 视为已创建
     */
    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        if (adminInitialPassword == null
                || adminInitialPassword.length() < MIN_ADMIN_PASSWORD_LENGTH
                || adminInitialPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_INPUT_BYTES) {
            log.warn("users 表为空，但 ADMIN_INITIAL_PASSWORD 未配置或不满足强度要求（至少 {} 位），跳过初始管理员创建",
                    MIN_ADMIN_PASSWORD_LENGTH);
            return;
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode(adminInitialPassword));
        admin.setName("系统管理员");
        admin.setRole(UserRole.ADMIN.name());
        admin.setStatus(1);
        try {
            userRepository.insert(admin);
            log.info("初始管理员已创建，用户名 admin");
        } catch (DuplicateKeyException e) {
            log.info("初始管理员已被其他实例创建，跳过");
        }
    }
}

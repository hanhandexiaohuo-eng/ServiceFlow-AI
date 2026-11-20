package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.dto.AuthResult;
import com.serviceflow.ai.auth.dto.ChangePasswordRequest;
import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.dto.UserResponse;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.auth.security.PasswordPolicy;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.TooManyRequestsException;
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
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * 认证业务：登录、改密、退出与初始管理员种子。
 *
 * 安全约定：
 * - 只存 BCrypt 哈希；不区分"用户不存在/密码错误/账号禁用"，防止用户名枚举。
 * - 连续失败会累加计数并短期锁定，同时做来源维度的内存限流。
 * - 会话版本 token_version 变化后旧 Token 立即失效（改密、退出、强制下线）。
 * - 所有登录成功/失败、改密、退出都写安全审计，审计内容不含密码或 Token。
 */
@Service
public class AuthService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt 算法本身的输入上限（字节），超长输入直接视为密码错误 */
    private static final int BCRYPT_MAX_INPUT_BYTES = 72;
    /** 初始密码最少 12 位；创建后强制首次登录改密 */
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 12;
    /** 连续失败达到该次数后锁定账号 */
    private static final int LOCK_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();
    private final String adminInitialPassword;

    public AuthService(UserRepository userRepository,
                       JwtService jwtService,
                       LoginAttemptGuard loginAttemptGuard,
                       AuditLogService auditLogService,
                       @Value("${ADMIN_INITIAL_PASSWORD:}") String adminInitialPassword) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.auditLogService = auditLogService;
        this.adminInitialPassword = adminInitialPassword;
    }

    public AuthResult login(LoginRequest req, String ip, String requestId) {
        String username = req.username() == null ? "" : req.username().trim();
        String throttleKey = username.toLowerCase(Locale.ROOT) + "|" + ip;
        try {
            loginAttemptGuard.assertAllowed(throttleKey);
        } catch (TooManyRequestsException e) {
            auditLogService.failure(null, username, "LOGIN", "USER", null, null, ip, requestId,
                    "rate_limited");
            throw e;
        }

        Optional<User> found = userRepository.findByUsername(username);
        User user = found.orElse(null);

        if (user != null && user.getLockedUntil() != null
                && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            auditLogService.failure(user.getId(), username, "LOGIN", "USER",
                    String.valueOf(user.getId()), null, ip, requestId, "account_locked");
            throw new TooManyRequestsException("登录尝试过于频繁，请稍后再试");
        }

        boolean passwordOk = user != null && passwordMatches(req.password(), user.getPassword());
        boolean enabled = user != null && user.getStatus() != null && user.getStatus() == 1;
        if (!passwordOk || !enabled) {
            loginAttemptGuard.recordFailure(throttleKey);
            if (user != null) {
                registerDbFailure(user);
            }
            auditLogService.failure(user == null ? null : user.getId(), username, "LOGIN", "USER",
                    user == null ? null : String.valueOf(user.getId()), null, ip, requestId, "bad_credentials");
            throw new UnauthorizedException("用户名或密码错误");
        }

        loginAttemptGuard.reset(throttleKey);
        userRepository.recordLoginSuccess(user.getId());
        auditLogService.success(user.getId(), user.getUsername(), "LOGIN", "USER",
                String.valueOf(user.getId()), null, ip, requestId);

        return new AuthResult(jwtService.issueToken(user), newCsrfToken(),
                new LoginResponse(UserResponse.from(user), isPasswordChangeRequired(user)));
    }

    /** 修改密码：校验当前密码、强度策略与复用，成功后旧会话全部失效并重新签发 */
    public AuthResult changePassword(Long userId, String username, ChangePasswordRequest req,
                                     String ip, String requestId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("未登录或登录已过期"));
        if (!passwordMatches(req.currentPassword(), user.getPassword())) {
            auditLogService.failure(userId, username, "PASSWORD_CHANGE", "USER",
                    String.valueOf(userId), null, ip, requestId, "bad_current_password");
            throw new UnauthorizedException("当前密码不正确");
        }
        PasswordPolicy.validate(req.newPassword(), user.getUsername());
        if (passwordEncoder.matches(req.newPassword(), user.getPassword())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }

        boolean wasForced = isPasswordChangeRequired(user);
        userRepository.updatePassword(userId, passwordEncoder.encode(req.newPassword()));
        User fresh = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("未登录或登录已过期"));

        auditLogService.success(userId, username, "PASSWORD_CHANGE", "USER",
                String.valueOf(userId), "forced=" + wasForced, ip, requestId);
        return new AuthResult(jwtService.issueToken(fresh), newCsrfToken(),
                new LoginResponse(UserResponse.from(fresh), false));
    }

    /** 退出：会话版本 +1，使该用户所有已签发 Token 立即失效 */
    public void logout(Long userId, String username, String ip, String requestId) {
        userRepository.incrementTokenVersion(userId);
        auditLogService.success(userId, username, "LOGOUT", "USER",
                String.valueOf(userId), null, ip, requestId);
    }

    private void registerDbFailure(User user) {
        int failed = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
        LocalDateTime lockedUntil = failed >= LOCK_THRESHOLD
                ? LocalDateTime.now().plusMinutes(LOCK_MINUTES)
                : null;
        userRepository.recordLoginFailure(user.getId(), failed, lockedUntil);
    }

    private boolean isPasswordChangeRequired(User user) {
        return user.getPasswordChangeRequired() != null && user.getPasswordChangeRequired() == 1;
    }

    private String newCsrfToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * BCrypt 只处理前 72 字节输入，超长会抛异常。对超长输入直接判定为不匹配，
     * 对外仍表现为统一的"用户名或密码错误"。
     */
    private boolean passwordMatches(String rawPassword, String hash) {
        if (rawPassword == null || hash == null
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_INPUT_BYTES) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }

    /**
     * 初始管理员种子：仅当 users 表为空且 ADMIN_INITIAL_PASSWORD 有效时创建 admin。
     * 创建后立即标记 password_change_required=1，首次登录必须改密并写审计。
     * - 表非空（重复启动）→ 静默跳过
     * - 密码未配置/太短 → WARN（不输出密码）
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
            User created = userRepository.insert(admin);
            userRepository.markPasswordChangeRequired(created.getId());
            log.info("初始管理员已创建，首次登录必须修改初始密码");
        } catch (DuplicateKeyException e) {
            log.info("初始管理员已被其他实例创建，跳过");
        }
    }
}

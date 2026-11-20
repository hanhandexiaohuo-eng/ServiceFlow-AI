package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.dto.AdminUserCreateRequest;
import com.serviceflow.ai.auth.dto.AdminUserResponse;
import com.serviceflow.ai.auth.dto.UserPasswordResetRequest;
import com.serviceflow.ai.auth.dto.UserProfileUpdateRequest;
import com.serviceflow.ai.auth.dto.UserRoleUpdateRequest;
import com.serviceflow.ai.auth.dto.UserStatusUpdateRequest;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.auth.security.PasswordPolicy;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 管理端用户管理：仅 ADMIN 可用。
 *
 * 约束：
 * - 不提供物理删除，停用即保留工单/调度/审计关联；
 * - 不能停用或降级最后一个启用管理员，也不能停用/降级自己；
 * - 密码只写入 BCrypt 哈希，审计与响应都不含明文；
 * - 所有写操作先鉴权、再校验、再落库、最后审计。
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String OBJECT_TYPE = "USER";

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminUserService(UserRepository userRepository, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    public PageResponse<AdminUserResponse> page(String keyword, String role, Integer status,
                                                int page, int size) {
        requireAdmin();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String safeRole = normalizeRoleFilter(role);
        String safeKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        long total = userRepository.countSearch(safeKeyword, safeRole, status);
        List<AdminUserResponse> list = userRepository.search(
                        safeKeyword, safeRole, status, (safePage - 1) * safeSize, safeSize).stream()
                .map(AdminUserResponse::from)
                .toList();
        return new PageResponse<>(list, total, safePage, safeSize);
    }

    @Transactional
    public AdminUserResponse create(AdminUserCreateRequest req, String ip, String requestId) {
        LoginUser actor = requireAdmin();
        String username = req.username().trim();
        String name = req.name().trim();
        String role = normalizeRole(req.role());
        String department = blankToNull(req.department());
        PasswordPolicy.validate(req.password(), username);
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setName(name);
        user.setRole(role);
        user.setStatus(1);
        user.setDepartment(department);
        User created;
        try {
            created = userRepository.insert(user);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已存在");
        }
        userRepository.markPasswordChangeRequired(created.getId());

        auditLogService.success(actor.id(), actor.username(), "USER_CREATE", OBJECT_TYPE,
                String.valueOf(created.getId()),
                "role=" + role + " department=" + (department == null ? "-" : department),
                ip, requestId);
        return AdminUserResponse.from(userRepository.findById(created.getId()).orElse(created));
    }

    @Transactional
    public AdminUserResponse updateProfile(Long id, UserProfileUpdateRequest req,
                                           String ip, String requestId) {
        LoginUser actor = requireAdmin();
        User target = requireUser(id);
        String department = blankToNull(req.department());
        userRepository.updateProfile(id, req.name().trim(), department);
        auditLogService.success(actor.id(), actor.username(), "USER_UPDATE", OBJECT_TYPE,
                String.valueOf(id),
                "name=" + req.name().trim() + " department=" + (department == null ? "-" : department),
                ip, requestId);
        return AdminUserResponse.from(requireUser(id));
    }

    @Transactional
    public AdminUserResponse updateStatus(Long id, UserStatusUpdateRequest req,
                                          String ip, String requestId) {
        LoginUser actor = requireAdmin();
        User target = requireUser(id);
        int next = req.status();
        if (next == 0) {
            if (target.getId().equals(actor.id())) {
                throw new ForbiddenException("不能停用当前登录账号");
            }
            if (UserRole.ADMIN.name().equals(target.getRole()) && userRepository.countActiveAdmins() <= 1) {
                throw new ForbiddenException("至少需要保留一个启用的管理员");
            }
        }
        userRepository.updateStatus(id, next);
        if (next == 0) {
            // 停用后旧会话立即失效
            userRepository.incrementTokenVersion(id);
        }
        auditLogService.success(actor.id(), actor.username(),
                next == 1 ? "USER_ENABLE" : "USER_DISABLE", OBJECT_TYPE, String.valueOf(id),
                "from=" + target.getStatus() + " to=" + next, ip, requestId);
        return AdminUserResponse.from(requireUser(id));
    }

    @Transactional
    public AdminUserResponse updateRole(Long id, UserRoleUpdateRequest req,
                                        String ip, String requestId) {
        LoginUser actor = requireAdmin();
        User target = requireUser(id);
        String nextRole = normalizeRole(req.role());
        if (target.getId().equals(actor.id())) {
            throw new ForbiddenException("不能修改当前登录账号的角色");
        }
        if (UserRole.ADMIN.name().equals(target.getRole()) && !UserRole.ADMIN.name().equals(nextRole)
                && userRepository.countActiveAdmins() <= 1) {
            throw new ForbiddenException("至少需要保留一个启用的管理员");
        }
        userRepository.updateRoleAndInvalidate(id, nextRole);
        auditLogService.success(actor.id(), actor.username(), "USER_ROLE_CHANGE", OBJECT_TYPE,
                String.valueOf(id), "from=" + target.getRole() + " to=" + nextRole, ip, requestId);
        return AdminUserResponse.from(requireUser(id));
    }

    @Transactional
    public void resetPassword(Long id, UserPasswordResetRequest req, String ip, String requestId) {
        LoginUser actor = requireAdmin();
        User target = requireUser(id);
        if (target.getId().equals(actor.id())) {
            throw new ForbiddenException("请通过「修改密码」修改自己的密码");
        }
        PasswordPolicy.validate(req.password(), target.getUsername());
        if (passwordEncoder.matches(req.password(), target.getPassword())) {
            throw new IllegalArgumentException("临时密码不能与当前密码相同");
        }
        userRepository.resetPassword(id, passwordEncoder.encode(req.password()));
        auditLogService.success(actor.id(), actor.username(), "USER_PASSWORD_RESET", OBJECT_TYPE,
                String.valueOf(id), "强制下次登录修改密码", ip, requestId);
    }

    private LoginUser requireAdmin() {
        LoginUser user = UserContext.getRequired();
        if (user.role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可执行用户管理操作");
        }
        return user;
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在：" + id));
    }

    private String normalizeRole(String role) {
        try {
            return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("角色不合法，只能是 ADMIN / AGENT / USER");
        }
    }

    private String normalizeRoleFilter(String role) {
        return role == null || role.isBlank() ? null : normalizeRole(role);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

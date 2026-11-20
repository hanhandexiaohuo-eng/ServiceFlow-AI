package com.serviceflow.ai.auth.controller;

import com.serviceflow.ai.auth.dto.AdminUserCreateRequest;
import com.serviceflow.ai.auth.dto.AdminUserResponse;
import com.serviceflow.ai.auth.dto.UserPasswordResetRequest;
import com.serviceflow.ai.auth.dto.UserProfileUpdateRequest;
import com.serviceflow.ai.auth.dto.UserRoleUpdateRequest;
import com.serviceflow.ai.auth.dto.UserStatusUpdateRequest;
import com.serviceflow.ai.auth.service.AdminUserService;
import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.web.ClientIp;
import com.serviceflow.ai.common.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端用户管理：全部写操作需 ADMIN、CSRF 校验，并写审计。
 * 不提供删除接口：停用即保留历史关联。
 */
@Tag(name = "11 用户管理", description = "用户列表、创建、启停用、角色、部门、密码重置（仅管理员）")
@RestController
@RequestMapping("/api/admin/users")
@Validated
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(adminUserService.page(keyword, role, status, page, size));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminUserResponse>> create(
            @Valid @RequestBody AdminUserCreateRequest req, HttpServletRequest request) {
        AdminUserResponse created = adminUserService.create(req,
                ClientIp.resolve(request), currentRequestId(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(created));
    }

    @PutMapping("/{id}/profile")
    public ApiResponse<AdminUserResponse> updateProfile(@PathVariable Long id,
                                                        @Valid @RequestBody UserProfileUpdateRequest req,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(adminUserService.updateProfile(id, req,
                ClientIp.resolve(request), currentRequestId(request)));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<AdminUserResponse> updateStatus(@PathVariable Long id,
                                                       @Valid @RequestBody UserStatusUpdateRequest req,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(adminUserService.updateStatus(id, req,
                ClientIp.resolve(request), currentRequestId(request)));
    }

    @PutMapping("/{id}/role")
    public ApiResponse<AdminUserResponse> updateRole(@PathVariable Long id,
                                                     @Valid @RequestBody UserRoleUpdateRequest req,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(adminUserService.updateRole(id, req,
                ClientIp.resolve(request), currentRequestId(request)));
    }

    @PostMapping("/{id}/password-reset")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody UserPasswordResetRequest req,
                                           HttpServletRequest request) {
        adminUserService.resetPassword(id, req,
                ClientIp.resolve(request), currentRequestId(request));
        return ApiResponse.ok(null);
    }

    private String currentRequestId(HttpServletRequest request) {
        String fromMdc = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return request.getHeader(RequestIdFilter.HEADER);
    }
}

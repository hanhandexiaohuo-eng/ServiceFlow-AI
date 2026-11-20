package com.serviceflow.ai.auth.controller;

import com.serviceflow.ai.auth.dto.UserOptionResponse;
import com.serviceflow.ai.auth.service.UserQueryService;
import com.serviceflow.ai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 启用用户列表，供分配/转交下拉使用；需要登录。 */
@Tag(name = "02 用户管理", description = "用户列表与角色查询")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserQueryService userQueryService;

    public UserController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    /**
     * assignable=true 时只返回管理员与处理人员，供工单分配/转交使用；
     * 默认保持原有行为（全部启用用户），避免影响其他调用方。
     */
    @GetMapping
    public ApiResponse<List<UserOptionResponse>> list(
            @RequestParam(required = false) Boolean assignable) {
        return ApiResponse.ok(Boolean.TRUE.equals(assignable)
                ? userQueryService.listAssignable()
                : userQueryService.listActive());
    }
}

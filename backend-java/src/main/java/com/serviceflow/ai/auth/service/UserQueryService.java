package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.dto.UserOptionResponse;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 用户查询：分配处理人只允许选择启用的真实账号，禁止自由文本。 */
@Service
public class UserQueryService {

    /** 可承接工单的角色：管理员与处理人员 */
    private static final Set<String> ASSIGNABLE_ROLES =
            Set.of(UserRole.ADMIN.name(), UserRole.AGENT.name());

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<UserOptionResponse> listActive() {
        return userRepository.findAllActive().stream().map(UserOptionResponse::from).toList();
    }

    /** 可被分配工单的启用账号：管理员与处理人员，普通 USER 不再作为处理人候选。 */
    public List<UserOptionResponse> listAssignable() {
        return userRepository.findAllActive().stream()
                .filter(u -> ASSIGNABLE_ROLES.contains(u.getRole()))
                .map(UserOptionResponse::from)
                .toList();
    }

    /**
     * 按显示名或登录名解析启用用户。
     * 同名用户存在时拒绝，避免把工单分配给不确定的人。
     */
    public User requireActiveByName(String rawName) {
        return findActiveByName(rawName);
    }

    /** 分配/转交专用：只允许管理员与处理人员，普通用户不能承接工单。 */
    public User requireActiveAssigneeByName(String rawName) {
        User user = findActiveByName(rawName);
        if (!ASSIGNABLE_ROLES.contains(user.getRole())) {
            throw new IllegalArgumentException("处理人必须是管理员或处理人员：" + user.getName());
        }
        return user;
    }

    private User findActiveByName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("处理人不能为空");
        }
        List<User> matches = userRepository.findAllActive().stream()
                .filter(u -> name.equals(u.getName()) || name.equalsIgnoreCase(u.getUsername()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("处理人不存在或已禁用：" + name);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("存在同名用户，请先唯一化显示名：" + name);
        }
        return matches.get(0);
    }
}

package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.dispatch.dto.AgentAvailabilityRequest;
import com.serviceflow.ai.dispatch.dto.AgentCreateRequest;
import com.serviceflow.ai.dispatch.dto.AgentSkillItem;
import com.serviceflow.ai.dispatch.dto.AgentSkillsRequest;
import com.serviceflow.ai.dispatch.dto.AgentUpdateRequest;
import com.serviceflow.ai.dispatch.entity.AgentProfile;
import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.entity.AgentSkillRelation;
import com.serviceflow.ai.dispatch.enums.AgentAvailability;
import com.serviceflow.ai.dispatch.repository.AgentProfileRepository;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.vo.AgentResponse;
import com.serviceflow.ai.dispatch.vo.AgentSkillResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 人员管理：复用 users 作为人员主体，业务字段放在 agent_profiles。
 * 角色提升与新账号创建仅管理员可执行，请求体无法注入 role。
 */
@Service
public class AgentService {

    private static final int DEFAULT_MAX_CONCURRENT = 5;

    private final UserRepository userRepository;
    private final AgentProfileRepository profileRepository;
    private final AgentSkillRepository skillRepository;
    private final DispatchSecurity security;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AgentService(UserRepository userRepository,
                        AgentProfileRepository profileRepository,
                        AgentSkillRepository skillRepository,
                        DispatchSecurity security) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.skillRepository = skillRepository;
        this.security = security;
    }

    public List<AgentResponse> list(String keyword, String availability, String skillCode) {
        security.requireAdminOrAgent();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<User> agents = userRepository.findAll().stream()
                .filter(u -> UserRole.AGENT.name().equals(u.getRole()))
                .toList();
        List<AgentResponse> responses = toResponses(agents);
        String targetAvailability = availability == null || availability.isBlank()
                ? null : AgentAvailability.parse(availability).name();
        String targetSkill = skillCode == null || skillCode.isBlank() ? null : skillCode.trim().toUpperCase();
        return responses.stream()
                .filter(r -> kw.isEmpty()
                        || r.name().toLowerCase().contains(kw)
                        || r.username().toLowerCase().contains(kw))
                .filter(r -> targetAvailability == null || targetAvailability.equals(r.effectiveAvailability()))
                .filter(r -> targetSkill == null || r.skills().stream()
                        .anyMatch(s -> targetSkill.equals(s.skillCode())))
                .toList();
    }

    public AgentResponse get(Long id) {
        security.requireAdminOrAgent();
        return toResponses(List.of(requireAgent(id))).get(0);
    }

    @Transactional
    public AgentResponse create(AgentCreateRequest req) {
        security.requireAdmin();
        User user;
        if (req.userId() != null) {
            user = userRepository.findById(req.userId())
                    .orElseThrow(() -> new NotFoundException("用户不存在：id=" + req.userId()));
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new IllegalArgumentException("账号已禁用，无法设为处理人员");
            }
            if (UserRole.ADMIN.name().equals(user.getRole())) {
                throw new IllegalArgumentException("管理员账号无需设为处理人员");
            }
            if (!UserRole.AGENT.name().equals(user.getRole()) && !UserRole.USER.name().equals(user.getRole())) {
                throw new IllegalArgumentException("该账号角色不支持转为处理人员");
            }
            if (profileRepository.exists(user.getId())) {
                throw new ConflictException("该用户已是处理人员");
            }
            if (!UserRole.AGENT.name().equals(user.getRole())) {
                userRepository.updateRole(user.getId(), UserRole.AGENT.name());
                user.setRole(UserRole.AGENT.name());
            }
        } else {
            user = createAgentUser(req);
        }

        AgentProfile profile = new AgentProfile();
        profile.setUserId(user.getId());
        profile.setMaxConcurrent(req.maxConcurrent() == null ? DEFAULT_MAX_CONCURRENT : req.maxConcurrent());
        profile.setAvailability(parseAvailability(req.availability(), AgentAvailability.AVAILABLE).name());
        profileRepository.insert(profile);

        if (req.skills() != null && !req.skills().isEmpty()) {
            replaceRelations(user.getId(), req.skills());
        }
        return toResponses(List.of(user)).get(0);
    }

    @Transactional
    public AgentResponse update(Long id, AgentUpdateRequest req) {
        security.requireAdmin();
        requireAgent(id);
        AgentProfile profile = requireProfile(id);
        if (req.maxConcurrent() != null) {
            profile.setMaxConcurrent(req.maxConcurrent());
        }
        if (req.availability() != null && !req.availability().isBlank()) {
            profile.setAvailability(AgentAvailability.parse(req.availability()).name());
        }
        profileRepository.update(profile);
        return get(id);
    }

    public List<AgentSkillResponse> listSkills(Long id) {
        security.requireAdminOrAgent();
        requireAgent(id);
        return skillsOf(id);
    }

    @Transactional
    public List<AgentSkillResponse> replaceSkills(Long id, AgentSkillsRequest req) {
        security.requireAdmin();
        requireAgent(id);
        requireProfile(id);
        replaceRelations(id, req.skills());
        return skillsOf(id);
    }

    /**
     * 工作状态：管理员可设任意值；处理人员只能改自己的 AVAILABLE/BUSY/OFFLINE，
     * 不能自行禁用（DISABLED 仅管理员可设），账号禁用时任何状态调整都会被拒绝。
     */
    @Transactional
    public AgentResponse updateAvailability(Long id, AgentAvailabilityRequest req) {
        LoginUser operator = security.current();
        boolean admin = operator.role() == UserRole.ADMIN;
        boolean selfAgent = operator.role() == UserRole.AGENT && operator.id().equals(id);
        if (!admin && !selfAgent) {
            throw new ForbiddenException("仅管理员或本人可调整人员状态");
        }
        AgentAvailability target = AgentAvailability.parse(req.availability());
        if (!admin && target == AgentAvailability.DISABLED) {
            throw new ForbiddenException("仅管理员可禁用人员");
        }
        User user = requireAgent(id);
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("账号已禁用，无法调整人员状态");
        }
        AgentProfile profile = requireProfile(id);
        profile.setAvailability(target.name());
        profileRepository.update(profile);
        return get(id);
    }

    /** 调度/负载使用：读取全部 AGENT 用户（含账号禁用，便于展示真实状态）。 */
    public List<User> listAgentUsers() {
        return userRepository.findAll().stream()
                .filter(u -> UserRole.AGENT.name().equals(u.getRole()))
                .toList();
    }

    public User requireAgent(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("处理人员不存在：id=" + id));
        if (!UserRole.AGENT.name().equals(user.getRole())) {
            throw new NotFoundException("处理人员不存在：id=" + id);
        }
        return user;
    }

    public AgentProfile requireProfile(Long id) {
        return profileRepository.findByUserId(id)
                .orElseThrow(() -> new NotFoundException("人员档案不存在：id=" + id));
    }

    public AgentProfile profileOrDefault(User user) {
        return profileRepository.findByUserId(user.getId()).orElseGet(() -> defaultProfile(user));
    }

    private AgentProfile defaultProfile(User user) {
        AgentProfile profile = new AgentProfile();
        profile.setUserId(user.getId());
        profile.setMaxConcurrent(DEFAULT_MAX_CONCURRENT);
        profile.setAvailability(AgentAvailability.AVAILABLE.name());
        profile.setCreatedAt(user.getCreatedAt());
        profile.setUpdatedAt(user.getUpdatedAt());
        return profile;
    }

    public static String effectiveAvailability(User user, AgentProfile profile) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            return AgentAvailability.DISABLED.name();
        }
        return profile.getAvailability() == null ? AgentAvailability.AVAILABLE.name() : profile.getAvailability();
    }

    public List<AgentResponse> toResponses(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(User::getId).toList();
        Map<Long, AgentProfile> profiles = profileRepository.findAll().stream()
                .collect(Collectors.toMap(AgentProfile::getUserId, Function.identity(), (a, b) -> a));
        Map<Long, List<AgentSkillRelation>> relations = skillRepository.findRelationsByAgentIds(ids).stream()
                .collect(Collectors.groupingBy(AgentSkillRelation::getAgentId));
        Map<Long, AgentSkill> catalog = skillRepository.findAll().stream()
                .collect(Collectors.toMap(AgentSkill::getId, Function.identity(), (a, b) -> a));

        List<AgentResponse> result = new ArrayList<>();
        for (User user : users) {
            AgentProfile profile = profiles.get(user.getId());
            if (profile == null) {
                profile = defaultProfile(user);
            }
            List<AgentSkillResponse> skills = mapSkills(relations.getOrDefault(user.getId(), List.of()), catalog);
            result.add(new AgentResponse(
                    user.getId(), user.getUsername(), user.getName(), user.getRole(), user.getStatus(),
                    profile.getAvailability(), effectiveAvailability(user, profile), profile.getMaxConcurrent(),
                    skills,
                    profile.getCreatedAt() != null ? profile.getCreatedAt() : user.getCreatedAt(),
                    profile.getUpdatedAt() != null ? profile.getUpdatedAt() : user.getUpdatedAt()));
        }
        return result;
    }

    public List<AgentSkillResponse> skillsOf(Long agentId) {
        Map<Long, AgentSkill> catalog = skillRepository.findAll().stream()
                .collect(Collectors.toMap(AgentSkill::getId, Function.identity(), (a, b) -> a));
        return mapSkills(skillRepository.findRelationsByAgentId(agentId), catalog);
    }

    private List<AgentSkillResponse> mapSkills(List<AgentSkillRelation> relations, Map<Long, AgentSkill> catalog) {
        List<AgentSkillResponse> list = new ArrayList<>();
        for (AgentSkillRelation relation : relations) {
            AgentSkill skill = catalog.get(relation.getSkillId());
            if (skill == null) {
                continue;
            }
            list.add(new AgentSkillResponse(skill.getId(), skill.getSkillCode(), skill.getSkillName(),
                    relation.getProficiencyLevel(), AgentSkillResponse.label(relation.getProficiencyLevel())));
        }
        return list;
    }

    private void replaceRelations(Long agentId, List<AgentSkillItem> items) {
        Map<Long, Integer> levels = new LinkedHashMap<>();
        for (AgentSkillItem item : items) {
            skillRepository.findById(item.skillId())
                    .orElseThrow(() -> new IllegalArgumentException("技能不存在：id=" + item.skillId()));
            levels.put(item.skillId(), item.proficiencyLevel());
        }
        skillRepository.deleteRelationsByAgentId(agentId);
        levels.forEach((skillId, level) -> skillRepository.insertRelation(agentId, skillId, level));
    }

    private User createAgentUser(AgentCreateRequest req) {
        String username = trim(req.username());
        String name = trim(req.name());
        String password = req.password();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new IllegalArgumentException("密码长度需为 6-72 个字符");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ConflictException("用户名已存在：" + username);
        }
        Optional<User> sameName = userRepository.findAll().stream()
                .filter(u -> name.equals(u.getName()))
                .findFirst();
        if (sameName.isPresent()) {
            throw new ConflictException("显示名已存在，请使用唯一显示名：" + name);
        }
        User user = new User();
        user.setUsername(username);
        user.setName(name);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(UserRole.AGENT.name());
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.insert(user);
    }

    private AgentAvailability parseAvailability(String value, AgentAvailability defaultValue) {
        return value == null || value.isBlank() ? defaultValue : AgentAvailability.parse(value);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

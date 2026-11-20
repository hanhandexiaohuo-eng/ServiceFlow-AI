package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.dispatch.dto.AgentAvailabilityRequest;
import com.serviceflow.ai.dispatch.dto.AgentCreateRequest;
import com.serviceflow.ai.dispatch.dto.AgentSkillItem;
import com.serviceflow.ai.dispatch.dto.AgentSkillsRequest;
import com.serviceflow.ai.dispatch.entity.AgentProfile;
import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.entity.AgentSkillRelation;
import com.serviceflow.ai.dispatch.repository.AgentProfileRepository;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.vo.AgentResponse;
import com.serviceflow.ai.dispatch.vo.AgentSkillResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 人员管理：角色边界、账号启用与工作状态分离、技能替换。 */
class AgentServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentProfileRepository profileRepository = mock(AgentProfileRepository.class);
    private final AgentSkillRepository skillRepository = mock(AgentSkillRepository.class);
    private final AgentService service = new AgentService(
            userRepository, profileRepository, skillRepository, new DispatchSecurity());

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void login(long id, UserRole role) {
        UserContext.set(new LoginUser(id, "tester", "测试用户", role));
    }

    private User user(long id, String username, String name, String role, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    private AgentProfile profile(long id, int max, String availability) {
        AgentProfile p = new AgentProfile();
        p.setUserId(id);
        p.setMaxConcurrent(max);
        p.setAvailability(availability);
        return p;
    }

    private AgentSkill skill(long id, String code, String name) {
        AgentSkill s = new AgentSkill();
        s.setId(id);
        s.setSkillCode(code);
        s.setSkillName(name);
        return s;
    }

    private AgentSkillRelation relation(long agentId, long skillId, int level) {
        AgentSkillRelation r = new AgentSkillRelation();
        r.setAgentId(agentId);
        r.setSkillId(skillId);
        r.setProficiencyLevel(level);
        return r;
    }

    @Test
    void normalUserCannotListAgents() {
        login(1, UserRole.USER);
        assertThrows(ForbiddenException.class, () -> service.list(null, null, null));
        verify(userRepository, never()).findAll();
    }

    @Test
    void listReturnsOnlyAgentsWithSkillsAndEffectiveState() {
        login(1, UserRole.ADMIN);
        User active = user(10, "zhangsan", "张三", UserRole.AGENT.name(), 1);
        User disabled = user(11, "lisi", "李四", UserRole.AGENT.name(), 0);
        User normal = user(12, "wangwu", "王五", UserRole.USER.name(), 1);
        when(userRepository.findAll()).thenReturn(List.of(active, disabled, normal));
        when(profileRepository.findAll()).thenReturn(List.of(profile(10, 3, "BUSY")));
        when(skillRepository.findAll()).thenReturn(List.of(skill(1, "JAVA", "Java 开发")));
        when(skillRepository.findRelationsByAgentIds(any())).thenReturn(List.of(relation(10, 1, 3)));

        List<AgentResponse> list = service.list(null, null, null);

        assertEquals(2, list.size());
        AgentResponse first = list.get(0);
        assertEquals("BUSY", first.availability());
        assertEquals("BUSY", first.effectiveAvailability());
        assertEquals(3, first.maxConcurrent());
        assertEquals(1, first.skills().size());
        assertEquals("JAVA", first.skills().get(0).skillCode());
        assertEquals("高级", first.skills().get(0).proficiencyLabel());
        // 账号禁用优先于档案里的 AVAILABLE，有效状态必须是 DISABLED
        assertEquals("DISABLED", list.get(1).effectiveAvailability());
    }

    @Test
    void agentCannotCreateAgent() {
        login(5, UserRole.AGENT);
        AgentCreateRequest req = new AgentCreateRequest(
                null, "agent9", "新员工", "secret123", 5, null, null);
        assertThrows(ForbiddenException.class, () -> service.create(req));
        verify(userRepository, never()).insert(any());
    }

    @Test
    void createNewAgentForcesAgentRoleAndHashesPassword() {
        login(1, UserRole.ADMIN);
        when(userRepository.findByUsername("agent9")).thenReturn(Optional.empty());
        when(userRepository.findAll()).thenReturn(List.of());
        when(userRepository.insert(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(100L);
            return u;
        });
        when(skillRepository.findById(1L)).thenReturn(Optional.of(skill(1, "JAVA", "Java 开发")));
        when(skillRepository.findAll()).thenReturn(List.of(skill(1, "JAVA", "Java 开发")));
        when(skillRepository.findRelationsByAgentIds(any())).thenReturn(List.of(relation(100, 1, 3)));
        when(profileRepository.findAll()).thenReturn(List.of(profile(100, 3, "AVAILABLE")));

        AgentResponse resp = service.create(new AgentCreateRequest(
                null, "agent9", "新员工", "secret123", 3, "AVAILABLE",
                List.of(new AgentSkillItem(1L, 3))));

        assertEquals(100L, resp.id());
        assertEquals("AGENT", resp.role());
        assertEquals(3, resp.maxConcurrent());
        assertEquals(1, resp.skills().size());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insert(captor.capture());
        assertNotEquals("secret123", captor.getValue().getPassword());
        assertTrue(captor.getValue().getPassword().startsWith("$2"));
        verify(profileRepository).insert(any(AgentProfile.class));
        verify(skillRepository).deleteRelationsByAgentId(100L);
        verify(skillRepository).insertRelation(100L, 1L, 3);
        // 请求体没有 role 字段，服务端不应走角色修改
        verify(userRepository, never()).updateRole(anyLong(), any());
    }

    @Test
    void promoteExistingUserToAgent() {
        login(1, UserRole.ADMIN);
        User existing = user(7, "normal", "赵六", UserRole.USER.name(), 1);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(profileRepository.exists(7L)).thenReturn(false);
        when(profileRepository.findAll()).thenReturn(List.of());
        when(skillRepository.findRelationsByAgentIds(any())).thenReturn(List.of());
        when(skillRepository.findAll()).thenReturn(List.of());

        AgentResponse resp = service.create(new AgentCreateRequest(
                7L, null, null, null, 4, null, null));

        assertEquals("AGENT", resp.role());
        verify(userRepository).updateRole(7L, UserRole.AGENT.name());
    }

    @Test
    void promoteRejectsExistingProfileAndAdminAccount() {
        login(1, UserRole.ADMIN);
        User existing = user(7, "normal", "赵六", UserRole.USER.name(), 1);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(profileRepository.exists(7L)).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.create(new AgentCreateRequest(
                7L, null, null, null, null, null, null)));

        User admin = user(8, "admin", "管理员", UserRole.ADMIN.name(), 1);
        when(userRepository.findById(8L)).thenReturn(Optional.of(admin));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AgentCreateRequest(
                        8L, null, null, null, null, null, null)));
        assertEquals("管理员账号无需设为处理人员", e.getMessage());
    }

    @Test
    void promoteRejectsDisabledAccount() {
        login(1, UserRole.ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(
                user(7, "normal", "赵六", UserRole.USER.name(), 0)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AgentCreateRequest(
                        7L, null, null, null, null, null, null)));
        assertEquals("账号已禁用，无法设为处理人员", e.getMessage());
    }

    @Test
    void agentCanChangeOwnAvailability() {
        login(5, UserRole.AGENT);
        User self = user(5, "agent5", "张三", UserRole.AGENT.name(), 1);
        AgentProfile profile = profile(5, 5, "AVAILABLE");
        when(userRepository.findById(5L)).thenReturn(Optional.of(self));
        when(profileRepository.findByUserId(5L)).thenReturn(Optional.of(profile));
        when(profileRepository.findAll()).thenReturn(List.of(profile));
        when(skillRepository.findRelationsByAgentIds(any())).thenReturn(List.of());
        when(skillRepository.findAll()).thenReturn(List.of());

        AgentResponse resp = service.updateAvailability(5L,
                new AgentAvailabilityRequest("BUSY"));

        assertEquals("BUSY", resp.availability());
        verify(profileRepository).update(profile);
    }

    @Test
    void agentCannotChangeOthersOrDisableSelf() {
        login(5, UserRole.AGENT);
        assertThrows(ForbiddenException.class, () -> service.updateAvailability(6L,
                new AgentAvailabilityRequest("BUSY")));
        assertThrows(ForbiddenException.class, () -> service.updateAvailability(5L,
                new AgentAvailabilityRequest("DISABLED")));
        verify(profileRepository, never()).update(any());
    }

    @Test
    void disabledAccountCannotChangeAvailability() {
        login(1, UserRole.ADMIN);
        when(userRepository.findById(5L)).thenReturn(Optional.of(
                user(5, "agent5", "张三", UserRole.AGENT.name(), 0)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateAvailability(5L, new AgentAvailabilityRequest("AVAILABLE")));
        assertEquals("账号已禁用，无法调整人员状态", e.getMessage());
    }

    @Test
    void adminCanDisableAgent() {
        login(1, UserRole.ADMIN);
        User target = user(5, "agent5", "张三", UserRole.AGENT.name(), 1);
        AgentProfile profile = profile(5, 5, "AVAILABLE");
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));
        when(profileRepository.findByUserId(5L)).thenReturn(Optional.of(profile));
        when(profileRepository.findAll()).thenReturn(List.of(profile));
        when(skillRepository.findRelationsByAgentIds(any())).thenReturn(List.of());
        when(skillRepository.findAll()).thenReturn(List.of());

        AgentResponse resp = service.updateAvailability(5L,
                new AgentAvailabilityRequest("disabled"));

        assertEquals("DISABLED", resp.availability());
    }

    @Test
    void replaceSkillsRejectsUnknownSkill() {
        login(1, UserRole.ADMIN);
        when(userRepository.findById(5L)).thenReturn(Optional.of(
                user(5, "agent5", "张三", UserRole.AGENT.name(), 1)));
        when(profileRepository.findByUserId(5L)).thenReturn(Optional.of(profile(5, 5, "AVAILABLE")));
        when(skillRepository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.replaceSkills(5L, new AgentSkillsRequest(
                        List.of(new AgentSkillItem(99L, 2)))));
        assertEquals("技能不存在：id=99", e.getMessage());
        verify(skillRepository, never()).deleteRelationsByAgentId(anyLong());
    }

    @Test
    void replaceSkillsDeletesThenInserts() {
        login(1, UserRole.ADMIN);
        when(userRepository.findById(5L)).thenReturn(Optional.of(
                user(5, "agent5", "张三", UserRole.AGENT.name(), 1)));
        when(profileRepository.findByUserId(5L)).thenReturn(Optional.of(profile(5, 5, "AVAILABLE")));
        when(skillRepository.findById(1L)).thenReturn(Optional.of(skill(1, "JAVA", "Java 开发")));
        when(skillRepository.findAll()).thenReturn(List.of(skill(1, "JAVA", "Java 开发")));
        when(skillRepository.findRelationsByAgentId(5L)).thenReturn(List.of(relation(5, 1, 2)));

        List<AgentSkillResponse> skills = service.replaceSkills(5L,
                new AgentSkillsRequest(List.of(new AgentSkillItem(1L, 2))));

        assertEquals(1, skills.size());
        assertEquals("中级", skills.get(0).proficiencyLabel());
        verify(skillRepository).deleteRelationsByAgentId(5L);
        verify(skillRepository).insertRelation(5L, 1L, 2);
    }
}

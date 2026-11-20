package com.serviceflow.ai.dispatch.service;

import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.dispatch.dto.SkillCreateRequest;
import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.vo.SkillResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 技能字典：基础技能由迁移脚本写入，管理员可追加自定义技能。 */
@Service
public class SkillCatalogService {

    private final AgentSkillRepository skillRepository;
    private final DispatchSecurity security;

    public SkillCatalogService(AgentSkillRepository skillRepository, DispatchSecurity security) {
        this.skillRepository = skillRepository;
        this.security = security;
    }

    public List<SkillResponse> list() {
        return skillRepository.findAll().stream()
                .map(s -> new SkillResponse(s.getId(), s.getSkillCode(), s.getSkillName(), s.getDescription()))
                .toList();
    }

    @Transactional
    public SkillResponse create(SkillCreateRequest req) {
        security.requireAdmin();
        String name = req.skillName().trim();
        String code = normalizeCode(req.skillCode(), name);
        if (skillRepository.findByCode(code).isPresent()) {
            throw new ConflictException("技能编码已存在：" + code);
        }
        if (skillRepository.findByName(name).isPresent()) {
            throw new ConflictException("技能名称已存在：" + name);
        }
        AgentSkill skill = new AgentSkill();
        skill.setSkillCode(code);
        skill.setSkillName(name);
        skill.setDescription(req.description());
        skillRepository.insert(skill);
        return new SkillResponse(skill.getId(), skill.getSkillCode(), skill.getSkillName(), skill.getDescription());
    }

    /** 编码归一化为大写字母/数字/下划线；中文等无法转写时使用 CUSTOM_ 前缀回退。 */
    private String normalizeCode(String rawCode, String name) {
        String source = rawCode == null || rawCode.isBlank() ? name : rawCode;
        String code = source.trim().toUpperCase()
                .replaceAll("[^A-Z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (code.isEmpty()) {
            code = "CUSTOM_" + (System.currentTimeMillis() % 100_000_000L);
        }
        return code.length() > 32 ? code.substring(0, 32) : code;
    }
}

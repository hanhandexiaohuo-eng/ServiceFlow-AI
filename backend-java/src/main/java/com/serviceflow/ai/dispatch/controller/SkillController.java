package com.serviceflow.ai.dispatch.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.dispatch.dto.SkillCreateRequest;
import com.serviceflow.ai.dispatch.service.SkillCatalogService;
import com.serviceflow.ai.dispatch.vo.SkillResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 技能字典：读取供人员技能编辑使用，新增仅管理员。 */
@Tag(name = "06 技能字典", description = "技能列表维护")
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillCatalogService skillCatalogService;

    public SkillController(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    @GetMapping
    public ApiResponse<List<SkillResponse>> list() {
        return ApiResponse.ok(skillCatalogService.list());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SkillResponse>> create(@Valid @RequestBody SkillCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(skillCatalogService.create(req)));
    }
}

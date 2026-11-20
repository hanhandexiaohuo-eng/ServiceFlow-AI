package com.serviceflow.ai.dispatch.controller;

import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.dispatch.dto.AgentAvailabilityRequest;
import com.serviceflow.ai.dispatch.dto.AgentCreateRequest;
import com.serviceflow.ai.dispatch.dto.AgentSkillsRequest;
import com.serviceflow.ai.dispatch.dto.AgentUpdateRequest;
import com.serviceflow.ai.dispatch.service.AgentService;
import com.serviceflow.ai.dispatch.service.WorkloadService;
import com.serviceflow.ai.dispatch.vo.AgentResponse;
import com.serviceflow.ai.dispatch.vo.AgentSkillResponse;
import com.serviceflow.ai.dispatch.vo.WorkloadOverviewResponse;
import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 人员管理、技能与负载查询。权限在 Service 层按角色二次校验。 */
@Tag(name = "05 人员管理", description = "处理人、技能与负载")
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final WorkloadService workloadService;

    public AgentController(AgentService agentService, WorkloadService workloadService) {
        this.agentService = agentService;
        this.workloadService = workloadService;
    }

    @GetMapping
    public ApiResponse<List<AgentResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String availability,
            @RequestParam(required = false) String skillCode) {
        return ApiResponse.ok(agentService.list(keyword, availability, skillCode));
    }

    @GetMapping("/workload")
    public ApiResponse<List<WorkloadResponse>> workload() {
        return ApiResponse.ok(workloadService.list());
    }

    @GetMapping("/workload/overview")
    public ApiResponse<WorkloadOverviewResponse> workloadOverview() {
        return ApiResponse.ok(workloadService.overview());
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(agentService.get(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AgentResponse>> create(@Valid @RequestBody AgentCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(agentService.create(req)));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody AgentUpdateRequest req) {
        return ApiResponse.ok(agentService.update(id, req));
    }

    @GetMapping("/{id}/skills")
    public ApiResponse<List<AgentSkillResponse>> skills(@PathVariable Long id) {
        return ApiResponse.ok(agentService.listSkills(id));
    }

    @PutMapping("/{id}/skills")
    public ApiResponse<List<AgentSkillResponse>> replaceSkills(@PathVariable Long id,
                                                               @Valid @RequestBody AgentSkillsRequest req) {
        return ApiResponse.ok(agentService.replaceSkills(id, req));
    }

    @PutMapping("/{id}/availability")
    public ApiResponse<AgentResponse> updateAvailability(@PathVariable Long id,
                                                         @Valid @RequestBody AgentAvailabilityRequest req) {
        return ApiResponse.ok(agentService.updateAvailability(id, req));
    }

    @GetMapping("/{id}/workload")
    public ApiResponse<WorkloadResponse> workloadOf(@PathVariable Long id) {
        return ApiResponse.ok(workloadService.get(id));
    }
}

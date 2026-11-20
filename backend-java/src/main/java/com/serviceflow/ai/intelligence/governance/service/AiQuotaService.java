package com.serviceflow.ai.intelligence.governance.service;

import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.intelligence.client.AiServiceException;
import com.serviceflow.ai.intelligence.governance.model.AiGovernanceModels.Quota;
import com.serviceflow.ai.intelligence.governance.repository.AiCallLogRepository;
import com.serviceflow.ai.intelligence.governance.repository.AiGovernanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 调用配额：按全局 / 部门 / 用户 / 场景，统计日或月内的调用次数。
 * 配额未配置时不限制；配额读取失败不应阻断业务，只记录告警。
 */
@Service
public class AiQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AiQuotaService.class);

    private final AiGovernanceRepository governanceRepository;
    private final AiCallLogRepository callLogRepository;
    private final UserRepository userRepository;

    public AiQuotaService(AiGovernanceRepository governanceRepository,
                          AiCallLogRepository callLogRepository,
                          UserRepository userRepository) {
        this.governanceRepository = governanceRepository;
        this.callLogRepository = callLogRepository;
        this.userRepository = userRepository;
    }

    public void check(Long userId, String scenario) {
        List<Quota> quotas;
        try {
            quotas = governanceRepository.findQuotas();
        } catch (DataAccessException e) {
            log.warn("ai_quota_lookup_failed type={}", e.getClass().getSimpleName());
            return;
        }
        if (quotas.isEmpty()) {
            return;
        }
        String department = null;
        String role = null;
        for (Quota quota : quotas) {
            if (!quota.enabled()) {
                continue;
            }
            if ("DEPARTMENT".equals(quota.scopeType()) && department == null) {
                department = departmentOf(userId);
            }
            if ("ROLE".equals(quota.scopeType()) && role == null) {
                role = roleOf(userId);
            }
            LocalDateTime since = "MONTH".equals(quota.period())
                    ? LocalDate.now().withDayOfMonth(1).atStartOfDay()
                    : LocalDate.now().atStartOfDay();
            long used = callLogRepository.countSince(quota.scopeType(), userId, department, role,
                    scenario, since);
            if (used >= quota.maxCalls()) {
                throw new AiServiceException("AI_QUOTA_EXCEEDED",
                        "AI 调用配额已用尽（" + scopeLabel(quota) + "，上限 " + quota.maxCalls() + " 次）", 429);
            }
        }
    }

    public String departmentOf(Long userId) {
        try {
            return userRepository.findById(userId).map(user -> user.getDepartment()).orElse(null);
        } catch (DataAccessException e) {
            return null;
        }
    }

    public String roleOf(Long userId) {
        try {
            return userRepository.findById(userId).map(user -> user.getRole()).orElse(null);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private String scopeLabel(Quota quota) {
        return switch (quota.scopeType()) {
            case "GLOBAL" -> "全局";
            case "DEPARTMENT" -> "部门 " + quota.scopeValue();
            case "ROLE" -> "角色 " + quota.scopeValue();
            case "USER" -> "用户 " + quota.scopeValue();
            case "SCENARIO" -> "场景 " + quota.scopeValue();
            default -> quota.scopeType();
        };
    }
}

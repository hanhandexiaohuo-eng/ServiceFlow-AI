package com.serviceflow.ai.dispatch.strategy;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 技能匹配：必需技能必须全部具备（过滤阶段保证），此处分值反映熟练度。
 * SkillScore = 0.7 × 必需技能平均熟练度分 + 0.3 × 加分技能平均熟练度分；
 * 没有加分规则时直接使用必需技能均分；工单类型无技能规则时给中性偏高分。
 */
@Component
public class SkillMatchStrategy implements ScoreStrategy {

    public static final double NO_RULE_SCORE = 70;

    @Override
    public String dimension() {
        return "skill";
    }

    @Override
    public double score(ScoreInput input) {
        if (input.requiredSkillIds().isEmpty()) {
            return NO_RULE_SCORE;
        }
        double required = average(input.requiredSkillIds(), input.proficiencyBySkillId());
        if (input.optionalSkillIds().isEmpty()) {
            return clamp(required);
        }
        List<Long> matchedOptional = input.optionalSkillIds().stream()
                .filter(input.proficiencyBySkillId()::containsKey)
                .toList();
        double optional = matchedOptional.isEmpty() ? 0
                : average(matchedOptional, input.proficiencyBySkillId());
        return clamp(required * 0.7 + optional * 0.3);
    }

    private double average(java.util.Collection<Long> skillIds, java.util.Map<Long, Integer> levels) {
        double sum = 0;
        int count = 0;
        for (Long skillId : skillIds) {
            Integer level = levels.get(skillId);
            if (level == null) {
                continue;
            }
            sum += proficiencyScore(level);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    /** 1 初级 60 / 2 中级 75 / 3 高级 90 / 4 专家 100 */
    public static double proficiencyScore(int level) {
        return switch (level) {
            case 1 -> 60;
            case 2 -> 75;
            case 3 -> 90;
            case 4 -> 100;
            default -> 0;
        };
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }
}

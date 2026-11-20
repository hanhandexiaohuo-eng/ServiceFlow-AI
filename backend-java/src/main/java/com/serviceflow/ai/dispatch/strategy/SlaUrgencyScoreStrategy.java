package com.serviceflow.ai.dispatch.strategy;

import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.enums.SlaState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SLA 紧迫度（工单级）：直接使用 Phase 3 的 TicketSlaService 评估结果。
 * 已超时 100；预警 70-100；正常 0-70；完成态不参与调度，返回 0。
 * 预警窗口来自 sla.warning-minutes，与 TicketSlaService 保持同一口径。
 */
@Component
public class SlaUrgencyScoreStrategy implements ScoreStrategy {

    /** 与 TicketSlaService.DEFAULT_WARNING_MINUTES 对齐；脱离 Spring 时按此默认值计算 */
    static final long DEFAULT_WARNING_MINUTES = 120;

    @Value("${sla.warning-minutes:120}")
    private long warningMinutes = DEFAULT_WARNING_MINUTES;

    @Override
    public String dimension() {
        return "sla";
    }

    @Override
    public double score(ScoreInput input) {
        return urgency(input.sla(), input.slaHours(), warningMinutes);
    }

    /** 推荐响应与评分共用同一口径，避免两处公式漂移。 */
    public static double urgency(TicketSlaResponse sla, int slaHours) {
        return urgency(sla, slaHours, DEFAULT_WARNING_MINUTES);
    }

    public static double urgency(TicketSlaResponse sla, int slaHours, long warningMinutes) {
        SlaState state;
        try {
            state = SlaState.valueOf(sla.state());
        } catch (Exception e) {
            state = SlaState.ON_TRACK;
        }
        long remaining = sla.remainingMinutes();
        double score = switch (state) {
            case OVERDUE -> 100;
            case WARNING -> 70 + 30 * (1 - Math.min(warningMinutes, Math.max(0, remaining)) / (double) warningMinutes);
            case ON_TRACK -> {
                long total = Math.max(1, slaHours * 60L);
                double ratio = Math.max(0, Math.min(1, (double) remaining / total));
                yield Math.max(0, Math.min(70, 70 * (1 - ratio)));
            }
            case COMPLETED -> 0;
        };
        return Math.round(score * 10.0) / 10.0;
    }
}

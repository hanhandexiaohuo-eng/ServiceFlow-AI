package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.enums.SlaState;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SLA 策略与状态评估单元测试。 */
class TicketSlaServiceTest {

    private final TicketSlaService slaService = new TicketSlaService();

    private Ticket ticket(String priority, String status, LocalDateTime created, LocalDateTime due) {
        Ticket t = new Ticket();
        t.setPriority(priority);
        t.setStatus(status);
        t.setCreatedAt(created);
        t.setSlaDueAt(due);
        return t;
    }

    @Test
    void policyHoursMatchBusinessRules() {
        assertEquals(4, slaService.hours("URGENT"));
        assertEquals(8, slaService.hours("HIGH"));
        assertEquals(24, slaService.hours("MEDIUM"));
        assertEquals(72, slaService.hours("LOW"));
        assertEquals(4, slaService.policies().size());
    }

    @Test
    void dueAtUsesPriorityHours() {
        LocalDateTime created = LocalDateTime.of(2026, 11, 16, 9, 0);
        assertEquals(LocalDateTime.of(2026, 11, 16, 13, 0), slaService.dueAt(created, "URGENT"));
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 0), slaService.dueAt(created, "MEDIUM"));
    }

    @Test
    void freshTicketIsOnTrack() {
        LocalDateTime now = LocalDateTime.now();
        TicketSlaResponse sla = slaService.evaluate(
                ticket("HIGH", TicketStatus.PENDING.name(), now.minusMinutes(10), now.plusHours(7)));
        assertEquals(SlaState.ON_TRACK.name(), sla.state());
        assertTrue(sla.remainingMinutes() > 100);
    }

    @Test
    void nearDeadlineIsWarning() {
        LocalDateTime now = LocalDateTime.now();
        TicketSlaResponse sla = slaService.evaluate(
                ticket("MEDIUM", TicketStatus.PROCESSING.name(), now.minusHours(2), now.plusMinutes(60)));
        assertEquals(SlaState.WARNING.name(), sla.state());
    }

    @Test
    void pastDeadlineIsOverdue() {
        LocalDateTime now = LocalDateTime.now();
        TicketSlaResponse sla = slaService.evaluate(
                ticket("URGENT", TicketStatus.ACCEPTED.name(), now.minusHours(5), now.minusMinutes(30)));
        assertEquals(SlaState.OVERDUE.name(), sla.state());
        assertTrue(sla.remainingMinutes() < 0);
    }

    @Test
    void resolvedTicketStopsTimer() {
        LocalDateTime now = LocalDateTime.now();
        Ticket t = ticket("HIGH", TicketStatus.RESOLVED.name(), now.minusHours(3), now.plusHours(5));
        t.setResolvedAt(now.minusHours(2));
        TicketSlaResponse sla = slaService.evaluate(t);
        assertEquals(SlaState.COMPLETED.name(), sla.state());
        assertTrue(sla.elapsedPercent() > 0 && sla.elapsedPercent() <= 100);
        assertEquals(t.getResolvedAt(), sla.completedAt());
    }
}

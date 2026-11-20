package com.serviceflow.ai.dispatch.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 负载与绩效聚合：全部来自 tickets 的真实状态与时间戳。
 * 占用口径：ASSIGNED / ACCEPTED / PROCESSING；完成口径：RESOLVED / CLOSED。
 */
@Repository
public class WorkloadRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkloadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 单个人员的原始聚合数据。 */
    public record AgentTicketStats(
            long assigned,
            long accepted,
            long processing,
            long todayCompleted,
            long weekCompleted,
            Long avgHandlingMinutes,
            Double avgSlaMinutes,
            long completedSample,
            long slaAchieved
    ) {
        public long occupied() {
            return assigned + accepted + processing;
        }

        public static AgentTicketStats empty() {
            return new AgentTicketStats(0, 0, 0, 0, 0, null, null, 0, 0);
        }
    }

    public Map<Long, AgentTicketStats> stats(Collection<Long> agentIds,
                                             LocalDateTime todayStart,
                                             LocalDateTime weekStart) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Object[] ids = agentIds.toArray();
        String placeholders = String.join(",", Collections.nCopies(agentIds.size(), "?"));

        Map<Long, long[]> occupancy = new HashMap<>();
        String occupancySql = "SELECT assignee_id, status, COUNT(*) AS c FROM tickets "
                + "WHERE assignee_id IN (" + placeholders + ") "
                + "AND status IN ('ASSIGNED','ACCEPTED','PROCESSING') GROUP BY assignee_id, status";
        jdbcTemplate.query(occupancySql, rs -> {
            while (rs.next()) {
                long[] counts = occupancy.computeIfAbsent(rs.getLong("assignee_id"), k -> new long[3]);
                String status = rs.getString("status");
                long c = rs.getLong("c");
                if ("ASSIGNED".equals(status)) {
                    counts[0] = c;
                } else if ("ACCEPTED".equals(status)) {
                    counts[1] = c;
                } else if ("PROCESSING".equals(status)) {
                    counts[2] = c;
                }
            }
            return null;
        }, ids);

        Map<Long, long[]> completion = new HashMap<>();
        String completionSql = "SELECT assignee_id, "
                + "SUM(CASE WHEN COALESCE(resolved_at, closed_at) >= ? THEN 1 ELSE 0 END) AS week_count, "
                + "SUM(CASE WHEN COALESCE(resolved_at, closed_at) >= ? THEN 1 ELSE 0 END) AS today_count "
                + "FROM tickets WHERE assignee_id IN (" + placeholders + ") "
                + "AND status IN ('RESOLVED','CLOSED') GROUP BY assignee_id";
        Object[] completionArgs = new Object[2 + ids.length];
        completionArgs[0] = weekStart;
        completionArgs[1] = todayStart;
        System.arraycopy(ids, 0, completionArgs, 2, ids.length);
        jdbcTemplate.query(completionSql, rs -> {
            while (rs.next()) {
                completion.put(rs.getLong("assignee_id"),
                        new long[]{rs.getLong("today_count"), rs.getLong("week_count")});
            }
            return null;
        }, completionArgs);

        Map<Long, double[]> performance = new HashMap<>();
        Map<Long, long[]> performanceCounts = new HashMap<>();
        String performanceSql = "SELECT assignee_id, COUNT(*) AS completed, "
                + "SUM(CASE WHEN COALESCE(resolved_at, closed_at) <= sla_due_at THEN 1 ELSE 0 END) AS achieved, "
                + "AVG(TIMESTAMPDIFF(MINUTE, COALESCE(first_response_at, created_at), COALESCE(resolved_at, closed_at))) AS avg_handle, "
                + "AVG(TIMESTAMPDIFF(MINUTE, created_at, sla_due_at)) AS avg_sla "
                + "FROM tickets WHERE assignee_id IN (" + placeholders + ") "
                + "AND status IN ('RESOLVED','CLOSED') GROUP BY assignee_id";
        jdbcTemplate.query(performanceSql, rs -> {
            while (rs.next()) {
                long agentId = rs.getLong("assignee_id");
                performanceCounts.put(agentId,
                        new long[]{rs.getLong("completed"), rs.getLong("achieved")});
                double avgHandle = rs.getDouble("avg_handle");
                double avgSla = rs.getDouble("avg_sla");
                performance.put(agentId, new double[]{avgHandle, avgSla});
            }
            return null;
        }, ids);

        Map<Long, AgentTicketStats> result = new LinkedHashMap<>();
        for (Long agentId : agentIds) {
            long[] occ = occupancy.getOrDefault(agentId, new long[3]);
            long[] done = completion.getOrDefault(agentId, new long[2]);
            long[] counts = performanceCounts.getOrDefault(agentId, new long[2]);
            double[] perf = performance.getOrDefault(agentId, new double[]{0, 0});
            Long avgHandle = counts[0] > 0 ? Math.round(perf[0]) : null;
            Double avgSla = counts[0] > 0 ? round1(perf[1]) : null;
            result.put(agentId, new AgentTicketStats(
                    occ[0], occ[1], occ[2], done[0], done[1],
                    avgHandle, avgSla, counts[0], counts[1]));
        }
        return result;
    }

    public long countPending() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE status = 'PENDING'", Long.class);
        return count == null ? 0 : count;
    }

    /** 用于测试与服务的纯函数：百分比保留 1 位小数。 */
    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

package com.serviceflow.ai.analytics.controller;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.service.ReportService;
import com.serviceflow.ai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 综合报表接口：JSON 与 CSV 使用同一个 ReportService.build 生成数据，
 * 仅明细行上限不同（页面 2000、导出 10000），保证页面与导出数字一致。
 */
@Tag(name = "16 报表中心", description = "报表查询与导出")
@RestController
@RequestMapping("/api/analytics")
public class ReportController {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 报表 JSON（type 必填） */
    @GetMapping("/reports")
    public ApiResponse<Map<String, Object>> report(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(reportService.build(type,
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** CSV 导出：UTF-8 BOM，Excel 可直接打开 */
    @GetMapping("/reports/export")
    public void export(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId,
            HttpServletResponse response) throws IOException {
        // 先完成参数校验与数据生成，失败时仍由全局异常处理返回 JSON 400，不会写出半个文件
        Map<String, Object> report = reportService.build(type,
                buildQuery(startDate, endDate, ticketType, priority, assigneeId),
                ReportService.EXPORT_DETAIL_LIMIT);

        String reportType = String.valueOf(report.get("type")).toLowerCase(Locale.ROOT);
        String filename = "phase6-" + reportType + "-" + LocalDate.now().format(FILE_DATE) + ".csv";

        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        writeCsv(csv, report);
        response.getWriter().write(csv.toString());
        response.getWriter().flush();
    }

    @SuppressWarnings("unchecked")
    private static void writeCsv(StringBuilder csv, Map<String, Object> report) {
        Map<String, Object> range = (Map<String, Object>) report.get("range");
        Map<String, Object> filters = (Map<String, Object>) report.get("filters");
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) report.get("metrics");
        List<Map<String, Object>> columns = (List<Map<String, Object>>) report.get("detailColumns");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("detailRows");
        List<String> notes = (List<String>) report.get("notes");

        csvLine(csv, "报表名称", report.get("reportName"));
        csvLine(csv, "统计范围", range.get("label"));
        csvLine(csv, "筛选条件", filterText(filters));
        csvLine(csv, "生成时间", report.get("generatedAt"));

        csvLine(csv, "指标");
        csvLine(csv, "key", "label", "value", "formula");
        for (Map<String, Object> metric : metrics) {
            csvLine(csv, metric.get("key"), metric.get("label"), metric.get("value"), metric.get("formula"));
        }

        csvLine(csv, "字段说明");
        csvLine(csv, "key", "label");
        for (Map<String, Object> column : columns) {
            csvLine(csv, column.get("key"), column.get("label"));
        }

        csvLine(csv);
        String[] keys = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            keys[i] = String.valueOf(columns.get(i).get("key"));
        }
        csvLine(csv, (Object[]) keys);
        for (Map<String, Object> row : rows) {
            Object[] cells = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                cells[i] = row.get(keys[i]);
            }
            csvLine(csv, cells);
        }

        for (String note : notes) {
            if (note.startsWith("明细共")) {
                csvLine(csv, "说明", note);
            }
        }
    }

    private static String filterText(Map<String, Object> filters) {
        Object ticketType = filters.get("ticketType");
        Object priority = filters.get("priority");
        Object assigneeId = filters.get("assigneeId");
        Object assigneeName = filters.get("assigneeName");
        String assignee;
        if (assigneeId == null) {
            assignee = "全部";
        } else if (assigneeName == null) {
            assignee = String.valueOf(assigneeId);
        } else {
            assignee = assigneeName + "(" + assigneeId + ")";
        }
        return "工单类型=" + (ticketType == null ? "全部" : ticketType)
                + "; 优先级=" + (priority == null ? "全部" : priority)
                + "; 处理人=" + assignee;
    }

    private static void csvLine(StringBuilder csv, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(cells[i]));
        }
        csv.append("\r\n");
    }

    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        boolean needQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        String escaped = text.replace("\"", "\"\"");
        return needQuote ? '"' + escaped + '"' : escaped;
    }

    private static AnalyticsQuery buildQuery(String startDate, String endDate, String ticketType,
                                             String priority, Long assigneeId) {
        return new AnalyticsQuery(parseDate(startDate), parseDate(endDate),
                ticketType, priority, assigneeId);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
    }
}

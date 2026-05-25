package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.metrics.CadGeometryMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从清洗结果和任务线索构建最小审核证据包。
 */
@Component
@RequiredArgsConstructor
public class TaskContextBuilder {

    private static final List<String> FALLBACK_GROUPS = List.of("fire", "parking", "site_boundary", "road", "dimensions");

    private final ObjectMapper objectMapper;
    private final CadIrCleaner cadIrCleaner;
    private final IrViewService irViewService;
    private final PreCleanerAgent preCleanerAgent;
    private final CadGeometryMetricsService cadGeometryMetricsService;

    public JsonNode build(JsonNode drawingIr, ReviewTask task, List<ReviewRule> rules) {
        return preCleanerAgent.cleanForTask(task.getTaskId(), buildBaseContext(drawingIr, task, rules));
    }

    public JsonNode buildWithEvidencePack(JsonNode drawingIr, ReviewTask task, List<ReviewRule> rules, TaskEvidencePack evidencePack) {
        ObjectNode context = buildBaseContext(drawingIr, task, rules);
        context.set("evidencePack", objectMapper.valueToTree(evidencePack));
        ObjectNode repairQuality = objectMapper.createObjectNode();
        repairQuality.put("repairAttempted", true);
        repairQuality.put("repairStatus", evidencePack.getQuality() == null ? "UNKNOWN" : evidencePack.getQuality().getRepairStatus());
        repairQuality.put("repairEvidenceCount", evidencePack.getFoundEvidence() == null ? 0 : evidencePack.getFoundEvidence().size());
        context.set("evidenceRepair", repairQuality);
        return preCleanerAgent.cleanForTask(task.getTaskId(), context);
    }

    private ObjectNode buildBaseContext(JsonNode drawingIr, ReviewTask task, List<ReviewRule> rules) {
        JsonNode reviewContext = cadIrCleaner.buildReviewContext(drawingIr);
        JsonNode slicedIr = irViewService.slice(drawingIr, task.getEntityIds(), task.getLayerNames());
        ObjectNode context = objectMapper.createObjectNode();
        context.set("task", objectMapper.valueToTree(task));
        context.set("rules", objectMapper.valueToTree(rules));
        context.set("drawing_brief", drawingBrief(reviewContext, drawingIr));
        context.set("quality", reviewContext.path("quality").deepCopy());
        context.set("evidence_groups", selectGroups(reviewContext.path("evidence_groups"), task, rules));
        context.set("clean_texts", reviewContext.path("clean_texts").deepCopy());
        context.set("clean_dimensions", reviewContext.path("clean_dimensions").deepCopy());
        context.set("indicator_rows", selectIndicatorRows(reviewContext.path("indicator_rows"), task, rules));
        context.set("computed_metrics", objectMapper.valueToTree(
                cadGeometryMetricsService.calculateForTask(metricIr(slicedIr), task, rules)));
        context.set("selected_ir", slicedIr);
        context.put("context_policy", task.getContextPolicy());
        return context;
    }

    private ObjectNode metricIr(JsonNode slicedIr) {
        ObjectNode metricIr = objectMapper.createObjectNode();
        metricIr.set("entities", slicedIr.path("selectedEntities").deepCopy());
        metricIr.set("texts", slicedIr.path("selectedTexts").deepCopy());
        metricIr.set("dimensions", slicedIr.path("selectedDimensions").deepCopy());
        metricIr.set("layers", slicedIr.path("selectedLayers").deepCopy());
        copyIfPresent(metricIr, slicedIr, "summary");
        copyIfPresent(metricIr, slicedIr, "statistics");
        copyIfPresent(metricIr, slicedIr, "semantic");
        return metricIr;
    }

    private void copyIfPresent(ObjectNode target, JsonNode source, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && !value.isNull()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private ObjectNode drawingBrief(JsonNode reviewContext, JsonNode drawingIr) {
        ObjectNode brief = objectMapper.createObjectNode();
        brief.set("drawing", reviewContext.path("drawing").deepCopy());
        brief.set("summary", drawingIr.path("summary").deepCopy());
        brief.set("semantic", drawingIr.path("semantic").deepCopy());
        brief.set("statistics", drawingIr.path("statistics").deepCopy());
        return brief;
    }

    private ObjectNode selectGroups(JsonNode source, ReviewTask task, List<ReviewRule> rules) {
        ObjectNode result = objectMapper.createObjectNode();
        Set<String> groups = requestedGroups(task, rules);
        for (String group : groups) {
            JsonNode value = source.path(group);
            if (!value.isMissingNode() && !value.isNull()) {
                result.set(group, value.deepCopy());
            }
        }
        return result;
    }

    private JsonNode selectIndicatorRows(JsonNode source, ReviewTask task, List<ReviewRule> rules) {
        if (!source.isArray()) {
            return objectMapper.createArrayNode();
        }
        Set<String> groups = requestedGroups(task, rules);
        if (groups.contains("parking") || groups.contains("building_info")) {
            return source.deepCopy();
        }
        return objectMapper.createArrayNode();
    }

    private Set<String> requestedGroups(ReviewTask task, List<ReviewRule> rules) {
        Set<String> groups = new LinkedHashSet<>();
        if (task.getEvidenceGroups() != null) {
            groups.addAll(task.getEvidenceGroups());
        }
        String text = ((task.getCategory() == null ? "" : task.getCategory()) + " "
                + (task.getCheckItem() == null ? "" : task.getCheckItem()) + " "
                + ruleText(rules)).toLowerCase(Locale.ROOT);
        if (text.contains("消防") || text.contains("防火") || text.contains("疏散") || text.contains("fire")) {
            groups.add("fire");
            groups.add("road");
            groups.add("dimensions");
        }
        if (text.contains("停车") || text.contains("充电") || text.contains("parking")) {
            groups.add("parking");
            groups.add("dimensions");
        }
        if (text.contains("车位") || text.contains("机动车") || text.contains("非机动车") || text.contains("无障碍")) {
            groups.add("parking");
            groups.add("dimensions");
            groups.add("building_info");
        }
        if (text.contains("道路") || text.contains("车道") || text.contains("转弯半径") || text.contains("半径")) {
            groups.add("road");
            groups.add("dimensions");
            groups.add("site_boundary");
        }
        if (text.contains("日照") || text.contains("承诺书")) {
            groups.add("building_info");
            groups.add("site_boundary");
        }
        if (text.contains("竖向") || text.contains("高差") || text.contains("标高")) {
            groups.add("dimensions");
            groups.add("site_boundary");
            groups.add("building_info");
        }
        if (text.contains("红线") || text.contains("退距") || text.contains("用地") || text.contains("site")) {
            groups.add("site_boundary");
            groups.add("road");
            groups.add("building_info");
        }
        if (groups.isEmpty()) {
            groups.addAll(FALLBACK_GROUPS);
        }
        return groups;
    }

    private String ruleText(List<ReviewRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        return String.join(" ", rules.stream()
                .map(rule -> rule.getTitle() + " " + rule.getScope())
                .toList());
    }
}

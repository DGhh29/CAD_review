package com.luckycat.cadreview.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.metrics.strategy.AreaMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.CadMetricExecutionContext;
import com.luckycat.cadreview.metrics.strategy.CadMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.ConnectivityMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.CountMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.DimensionMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.DistanceMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.ElevationMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.RadiusMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.RatioMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.TextMetricStrategy;
import com.luckycat.cadreview.metrics.strategy.WidthMetricStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAD 通用几何/指标计算入口。
 */
@Service
public class CadGeometryMetricsService {

    private final ObjectMapper objectMapper;
    private final Map<MetricOperation, CadMetricStrategy> strategies;

    public CadGeometryMetricsService(ObjectMapper objectMapper, List<CadMetricStrategy> strategies) {
        this.objectMapper = objectMapper;
        this.strategies = new EnumMap<>(MetricOperation.class);
        for (CadMetricStrategy strategy : strategies) {
            this.strategies.put(strategy.operation(), strategy);
        }
    }

    public static CadGeometryMetricsService createDefault(ObjectMapper objectMapper) {
        return new CadGeometryMetricsService(objectMapper, List.of(
                new TextMetricStrategy(),
                new DimensionMetricStrategy(),
                new RadiusMetricStrategy(),
                new CountMetricStrategy(),
                new ElevationMetricStrategy(),
                new WidthMetricStrategy(),
                new DistanceMetricStrategy(),
                new AreaMetricStrategy(),
                new RatioMetricStrategy(),
                new ConnectivityMetricStrategy()
        ));
    }

    public List<CadMetricResult> calculate(JsonNode drawingIr, List<CadMetricRequest> requests) {
        List<CadMetricResult> results = new ArrayList<>();
        Map<String, CadMetricResult> previousResults = new LinkedHashMap<>();
        JsonNode source = drawingIr == null ? objectMapper.createObjectNode() : drawingIr;
        int index = 1;
        for (CadMetricRequest original : requests == null ? List.<CadMetricRequest>of() : requests) {
            CadMetricRequest request = normalizeRequest(original, "REQ-" + index);
            CadMetricStrategy strategy = strategies.get(request.getOperation());
            CadMetricResult result;
            if (strategy == null) {
                result = errorResult(request, "不支持的指标操作: " + request.getOperation());
            } else {
                try {
                    result = strategy.calculate(request, new CadMetricExecutionContext(source, objectMapper, previousResults));
                } catch (RuntimeException ex) {
                    result = errorResult(request, ex.getMessage());
                }
            }
            results.add(result);
            previousResults.put(result.getRequestId(), result);
            index++;
        }
        return results;
    }

    public List<CadMetricResult> calculateForTask(JsonNode drawingIr, ReviewTask task, List<ReviewRule> rules) {
        return calculate(drawingIr, requestsForTask(task, rules));
    }

    public List<CadMetricRequest> requestsForTask(ReviewTask task, List<ReviewRule> rules) {
        List<CadMetricRequest> requests = new ArrayList<>();
        for (ReviewRule rule : rules == null ? List.<ReviewRule>of() : rules) {
            if (rule.getMetricRequests() != null && !rule.getMetricRequests().isEmpty()) {
                int index = 1;
                for (CadMetricRequest metricRequest : rule.getMetricRequests()) {
                    requests.add(normalizeRuleRequest(metricRequest, rule, index++));
                }
            } else {
                requests.addAll(inferRequests(rule, task));
            }
        }
        return requests;
    }

    private List<CadMetricRequest> inferRequests(ReviewRule rule, ReviewTask task) {
        String text = normalize(String.join(" ",
                safe(task == null ? null : task.getCategory()),
                safe(task == null ? null : task.getCheckItem()),
                safe(rule.getTitle()),
                safe(rule.getScope()),
                safe(rule.getPromptFragment())));
        List<CadMetricRequest> requests = new ArrayList<>();
        String ruleId = rule.getId();

        if (containsAny(text, "日照", "承诺书", "日照报告")) {
            requests.add(request(ruleId, "FIND_TEXT", "日照分析报告/承诺书文本",
                    MetricOperation.FIND_TEXT,
                    target(List.of(), List.of("日照", "承诺书", "报告"), List.of("日照分析", "日照报告", "日照承诺书", "承诺书"), List.of(), List.of()),
                    1.0d,
                    MetricComparator.EXISTS,
                    "处"));
        }
        if (containsAny(text, "竖向", "高差", "标高")) {
            requests.add(request(ruleId, "MEASURE_ELEVATION_DIFF", "场地最大标高差",
                    MetricOperation.MEASURE_ELEVATION_DIFF,
                    target(List.of(), List.of("DIM_ELEV", "竖向", "标高", "高差"), List.of("标高", "高差", "竖向", "H=", "±"), List.of(), List.of()),
                    threshold(text, 5.0d),
                    MetricComparator.LESS_OR_EQUAL,
                    "m"));
        }
        if (containsAny(text, "转弯半径", "半径", "r=")) {
            requests.add(request(ruleId, "MEASURE_RADIUS", "道路转弯半径",
                    MetricOperation.MEASURE_RADIUS,
                    target(List.of("ARC", "CIRCLE"), List.of("道路", "车道", "road", "DIM", "PUB_DIM"), List.of("转弯半径", "半径", "R="), List.of(), List.of()),
                    threshold(text, 6.0d),
                    MetricComparator.GREATER_OR_EQUAL,
                    "m"));
        }
        if (containsAny(text, "道路", "车道", "疏散", "通道", "楼梯") && containsAny(text, "宽", "净宽", "宽度")) {
            Double defaultWidth = containsAny(text, "道路", "车道") ? 4.0d : null;
            requests.add(request(ruleId, "MEASURE_WIDTH", "通道/道路宽度",
                    MetricOperation.MEASURE_WIDTH,
                    target(List.of("LINE", "LWPOLYLINE", "POLYLINE"), List.of("道路", "车道", "疏散", "通道", "楼梯", "DIM", "PUB_DIM"), List.of("宽", "净宽", "宽度", "W=", "L="), List.of(), List.of()),
                    threshold(text, defaultWidth),
                    MetricComparator.GREATER_OR_EQUAL,
                    "m"));
        }
        if (containsAny(text, "面积", "防火分区")) {
            requests.add(request(ruleId, "MEASURE_AREA", "面积",
                    MetricOperation.MEASURE_AREA,
                    target(List.of("LWPOLYLINE", "POLYLINE", "HATCH"), List.of("防火", "分区", "面积", "建筑", "HATCH"), List.of("面积", "防火分区", "建筑面积"), List.of(), List.of()),
                    null,
                    null,
                    "㎡"));
        }
        if (containsAny(text, "消防设施", "消火栓", "喷淋", "报警")) {
            requests.add(request(ruleId, "COUNT_OBJECTS", "消防设施数量/线索",
                    MetricOperation.COUNT_OBJECTS,
                    target(List.of("INSERT"), List.of("消防", "EQUIP", "fire"), List.of("消防", "消火栓", "喷淋", "报警"), List.of("fire"), List.of()),
                    1.0d,
                    MetricComparator.EXISTS,
                    "个"));
        }
        if (containsAny(text, "无障碍", "停车")) {
            CadMetricTarget accessible = target(List.of("INSERT", "LWPOLYLINE", "POLYLINE"), List.of("无障碍", "停车", "车位"), List.of("无障碍", "无障碍车位"), List.of("parking"), List.of());
            CadMetricTarget totalParking = target(List.of("INSERT", "LWPOLYLINE", "POLYLINE"), List.of("停车", "车位", "PARK"), List.of("机动车停车位", "停车位", "车位", "普通机动车"), List.of("parking"), List.of("非机动车"));
            requests.add(request(ruleId, "COUNT_ACCESSIBLE_PARKING", "无障碍停车位数量",
                    MetricOperation.COUNT_OBJECTS, accessible, 1.0d, MetricComparator.GREATER_OR_EQUAL, "个"));
            requests.add(ratioRequest(ruleId, "RATIO_ACCESSIBLE_PARKING", "无障碍车位占比",
                    accessible, totalParking, 1.0d, percentThreshold(text, 0.02d), "ratio"));
        } else if (containsAny(text, "非机动车", "自行车")) {
            CadMetricTarget nonMotor = target(List.of("INSERT", "LWPOLYLINE", "POLYLINE"), List.of("非机动车", "自行车", "停车"), List.of("非机动车", "自行车"), List.of("parking"), List.of());
            CadMetricTarget area = target(List.of(), List.of("建筑", "指标", "面积"), List.of("建筑面积", "计容面积", "总建筑面积", "面积"), List.of(), List.of());
            requests.add(request(ruleId, "COUNT_NON_MOTOR_PARKING", "非机动车停车数量",
                    MetricOperation.COUNT_OBJECTS, nonMotor, null, null, "个"));
            requests.add(ratioRequest(ruleId, "RATIO_NON_MOTOR_PER_100M2", "非机动车车位/百平方米",
                    nonMotor, area, 0.01d, threshold(text, 1.0d), "个/100㎡"));
        } else if (containsAny(text, "机动车", "停车", "车位")) {
            CadMetricTarget motor = target(List.of("INSERT", "LWPOLYLINE", "POLYLINE"), List.of("停车", "车位", "PARK"), List.of("机动车停车位", "普通机动车", "停车位", "车位"), List.of("parking"), List.of("非机动车", "无障碍"));
            CadMetricTarget area = target(List.of(), List.of("建筑", "指标", "面积"), List.of("建筑面积", "计容面积", "总建筑面积", "面积"), List.of(), List.of());
            requests.add(request(ruleId, "COUNT_MOTOR_PARKING", "机动车停车数量",
                    MetricOperation.COUNT_OBJECTS, motor, null, null, "个"));
            requests.add(ratioRequest(ruleId, "RATIO_MOTOR_PER_100M2", "机动车车位/百平方米",
                    motor, area, 0.01d, threshold(text, 0.5d), "个/100㎡"));
        }
        if (containsAny(text, "连通", "闭合")) {
            requests.add(request(ruleId, "CHECK_CONNECTIVITY", "边界/道路连通性",
                    MetricOperation.CHECK_CONNECTIVITY,
                    target(List.of("LINE", "LWPOLYLINE", "POLYLINE"), List.of("道路", "边界", "红线", "防火"), List.of(), List.of(), List.of()),
                    1.0d,
                    MetricComparator.EQUAL,
                    "bool"));
        }
        return requests;
    }

    private CadMetricRequest normalizeRuleRequest(CadMetricRequest request, ReviewRule rule, int index) {
        CadMetricRequest normalized = normalizeRequest(request, rule.getId() + ":REQ-" + index);
        if (normalized.getRuleId() == null || normalized.getRuleId().isBlank()) {
            normalized.setRuleId(rule.getId());
        }
        return normalized;
    }

    private CadMetricRequest normalizeRequest(CadMetricRequest request, String fallbackRequestId) {
        CadMetricRequest normalized = request == null ? new CadMetricRequest() : request;
        if (normalized.getRequestId() == null || normalized.getRequestId().isBlank()) {
            normalized.setRequestId(fallbackRequestId);
        }
        if (normalized.getTarget() == null) {
            normalized.setTarget(new CadMetricTarget());
        }
        return normalized;
    }

    private CadMetricResult errorResult(CadMetricRequest request, String message) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("error", message == null ? "unknown error" : message);
        return CadMetricResult.builder()
                .requestId(request.getRequestId())
                .ruleId(request.getRuleId())
                .label(request.getLabel())
                .operation(request.getOperation())
                .status(MetricStatus.ERROR)
                .comparison("INSUFFICIENT_EVIDENCE")
                .confidence(0.0d)
                .notes(List.of(message == null ? "指标计算失败" : message))
                .details(details)
                .build();
    }

    private CadMetricRequest request(
            String ruleId,
            String suffix,
            String label,
            MetricOperation operation,
            CadMetricTarget target,
            Double requiredValue,
            MetricComparator comparator,
            String unit) {
        return CadMetricRequest.builder()
                .requestId(ruleId + ":" + suffix)
                .ruleId(ruleId)
                .label(label)
                .operation(operation)
                .target(target)
                .requiredValue(requiredValue)
                .comparator(comparator)
                .unit(unit)
                .build();
    }

    private CadMetricRequest ratioRequest(
            String ruleId,
            String suffix,
            String label,
            CadMetricTarget numerator,
            CadMetricTarget denominator,
            Double denominatorMultiplier,
            Double requiredValue,
            String unit) {
        return CadMetricRequest.builder()
                .requestId(ruleId + ":" + suffix)
                .ruleId(ruleId)
                .label(label)
                .operation(MetricOperation.COMPUTE_RATIO)
                .numeratorTarget(numerator)
                .denominatorTarget(denominator)
                .denominatorMultiplier(denominatorMultiplier)
                .requiredValue(requiredValue)
                .comparator(MetricComparator.GREATER_OR_EQUAL)
                .unit(unit)
                .build();
    }

    private CadMetricTarget target(
            List<String> entityTypes,
            List<String> layerHints,
            List<String> textHints,
            List<String> semanticHints,
            List<String> excludeHints) {
        return CadMetricTarget.builder()
                .entityTypes(entityTypes)
                .layerHints(layerHints)
                .textHints(textHints)
                .semanticHints(semanticHints)
                .excludeHints(excludeHints)
                .build();
    }

    private Double threshold(String text, Double fallback) {
        String source = text == null ? "" : text;
        Matcher constrained = Pattern.compile("(不应小于|不小于|不少于|不应少于|小于|大于|超过)[^\\d]{0,20}([-+]?\\d+(?:\\.\\d+)?)")
                .matcher(source);
        if (constrained.find()) {
            try {
                return Double.parseDouble(constrained.group(2));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        Matcher matcher = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)(?:m|米|个|㎡|平方米)").matcher(source);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                if (value > 0.0d && value <= 10_000.0d) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return fallback;
    }

    private Double percentThreshold(String text, Double fallback) {
        Matcher matcher = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)\\s*%").matcher(text == null ? "" : text);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1)) / 100.0d;
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return fallback;
    }

    private boolean containsAny(String text, String... hints) {
        String normalized = normalize(text);
        for (String hint : hints) {
            if (normalized.contains(normalize(hint))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

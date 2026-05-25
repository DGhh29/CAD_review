package com.luckycat.cadreview.metrics.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.metrics.CadMetricRequest;
import com.luckycat.cadreview.metrics.CadMetricResult;
import com.luckycat.cadreview.metrics.CadMetricTarget;
import com.luckycat.cadreview.metrics.EvidenceRef;
import com.luckycat.cadreview.metrics.MeasuredCandidate;
import com.luckycat.cadreview.metrics.MetricComparator;
import com.luckycat.cadreview.metrics.MetricStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractCadMetricStrategy implements CadMetricStrategy {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final double TEXT_ROW_Y_TOLERANCE = 350.0d;
    private static final double TEXT_ROW_MAX_X_DISTANCE = 12_000.0d;

    protected CadMetricTarget target(CadMetricRequest request) {
        return request.getTarget() == null ? new CadMetricTarget() : request.getTarget();
    }

    protected CadMetricResult result(
            CadMetricRequest request,
            MetricStatus status,
            Double measuredValue,
            List<EvidenceRef> evidenceRefs,
            List<String> notes,
            ObjectNode details) {
        return CadMetricResult.builder()
                .requestId(request.getRequestId())
                .ruleId(request.getRuleId())
                .label(request.getLabel())
                .operation(request.getOperation())
                .status(status)
                .measuredValue(measuredValue)
                .requiredValue(request.getRequiredValue())
                .unit(request.getUnit())
                .comparison(compare(status, measuredValue, request.getRequiredValue(), request.getComparator()))
                .confidence(confidence(status, evidenceRefs))
                .evidenceRefs(evidenceRefs == null ? List.of() : evidenceRefs)
                .notes(notes == null ? List.of() : notes)
                .details(details)
                .build();
    }

    protected CadMetricResult notFound(CadMetricRequest request, CadMetricExecutionContext context, String note) {
        ObjectNode details = context.objectMapper().createObjectNode();
        details.put("reason", note);
        return result(request, MetricStatus.NOT_FOUND, null, List.of(), List.of(note), details);
    }

    protected List<JsonNode> allNodes(JsonNode drawingIr, String arrayName) {
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node : iterable(drawingIr.path(arrayName))) {
            nodes.add(node);
        }
        return nodes;
    }

    protected Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections::emptyIterator;
        }
        return node::elements;
    }

    protected boolean matches(JsonNode node, CadMetricTarget target) {
        CadMetricTarget actual = target == null ? new CadMetricTarget() : target;
        if (!matchesType(node, actual.getEntityTypes())) {
            return false;
        }
        List<String> hints = new ArrayList<>();
        addAll(hints, actual.getLayerHints());
        addAll(hints, actual.getTextHints());
        addAll(hints, actual.getSemanticHints());
        if (hints.isEmpty()) {
            return true;
        }
        String haystack = normalize(String.join(" ",
                node.path("layer").asText(""),
                node.path("text").asText(""),
                node.path("semantic").asText(""),
                node.path("block").asText(""),
                node.path("type").asText(""),
                node.path("handle").asText("")));
        if (actual.getExcludeHints() != null) {
            for (String excludeHint : actual.getExcludeHints()) {
                String normalizedExcludeHint = normalize(excludeHint);
                if (!normalizedExcludeHint.isEmpty() && haystack.contains(normalizedExcludeHint)) {
                    return false;
                }
            }
        }
        for (String hint : hints) {
            String normalizedHint = normalize(hint);
            if (!normalizedHint.isEmpty() && haystack.contains(normalizedHint)) {
                return true;
            }
        }
        return false;
    }

    protected List<MeasuredCandidate> dimensionMeasurements(JsonNode drawingIr, CadMetricTarget target, Double requiredValue) {
        List<MeasuredCandidate> candidates = new ArrayList<>();
        for (JsonNode dimension : iterable(drawingIr.path("dimensions"))) {
            if (!matches(dimension, target)) {
                continue;
            }
            if (dimension.path("measurement").isNumber()) {
                double raw = dimension.path("measurement").asDouble();
                candidates.add(new MeasuredCandidate(
                        normalizeLength(raw, requiredValue),
                        dimension,
                        normalizationNotes(raw, requiredValue, "measurement")));
            }
            for (double value : numbers(dimension.path("text").asText(""))) {
                candidates.add(new MeasuredCandidate(
                        normalizeLength(value, requiredValue),
                        dimension,
                        normalizationNotes(value, requiredValue, "dimension_text")));
            }
        }
        return candidates;
    }

    protected List<MeasuredCandidate> numericTextCandidates(JsonNode drawingIr, CadMetricTarget target, Double requiredValue) {
        List<MeasuredCandidate> candidates = new ArrayList<>();
        List<JsonNode> texts = allNodes(drawingIr, "texts");
        collectNumbersFromMatchedTexts(texts, target, requiredValue, candidates);
        collectNearbyNumbers(texts, target, requiredValue, candidates);
        collectNumbersFromMatchedTexts(allNodes(drawingIr, "dimensions"), target, requiredValue, candidates);
        collectNumbersFromMatchedTexts(textEntities(drawingIr), target, requiredValue, candidates);
        return candidates;
    }

    protected List<MeasuredCandidate> textMatches(JsonNode drawingIr, CadMetricTarget target) {
        List<MeasuredCandidate> matches = new ArrayList<>();
        collectTextMatches(matches, allNodes(drawingIr, "texts"), target);
        collectTextMatches(matches, allNodes(drawingIr, "dimensions"), target);
        collectTextMatches(matches, textEntities(drawingIr), target);
        return matches;
    }

    protected List<EvidenceRef> evidenceRefs(List<MeasuredCandidate> candidates, int limit) {
        List<EvidenceRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MeasuredCandidate candidate : candidates) {
            EvidenceRef ref = evidenceRef(candidate.evidence(), "metric_candidate");
            String key = ref.getSource() + "|" + ref.getEntityId() + "|" + ref.getLayer() + "|" + ref.getText();
            if (seen.add(key)) {
                refs.add(ref);
            }
            if (refs.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return refs;
    }

    protected EvidenceRef evidenceRef(JsonNode node, String source) {
        return EvidenceRef.builder()
                .source(source)
                .entityId(entityId(node))
                .type(textOrNull(node.path("type").asText(null)))
                .layer(textOrNull(node.path("layer").asText(null)))
                .text(textOrNull(node.path("text").asText(null)))
                .point(point(node))
                .bbox(bbox(node))
                .build();
    }

    protected String entityId(JsonNode node) {
        String handle = node.path("handle").asText(null);
        if (handle != null && !handle.isBlank()) {
            return handle;
        }
        if (node.path("index").isNumber() || !node.path("index").asText("").isBlank()) {
            return "entity-" + node.path("index").asText();
        }
        return null;
    }

    protected List<Double> point(JsonNode node) {
        for (String field : List.of("point", "insert", "center", "start")) {
            JsonNode point = node.path(field);
            if (point.isArray() && point.size() >= 2) {
                return List.of(point.get(0).asDouble(), point.get(1).asDouble());
            }
        }
        return List.of();
    }

    protected List<Double> bbox(JsonNode node) {
        JsonNode bbox = node.path("bbox");
        if (bbox.has("min_x") && bbox.has("min_y") && bbox.has("max_x") && bbox.has("max_y")) {
            return List.of(
                    bbox.path("min_x").asDouble(),
                    bbox.path("min_y").asDouble(),
                    bbox.path("max_x").asDouble(),
                    bbox.path("max_y").asDouble());
        }
        return List.of();
    }

    protected List<double[]> points(JsonNode node) {
        List<double[]> points = new ArrayList<>();
        for (JsonNode point : iterable(node.path("points"))) {
            if (point.isArray() && point.size() >= 2) {
                points.add(new double[]{point.get(0).asDouble(), point.get(1).asDouble()});
            }
        }
        return points;
    }

    protected double pointDistance(List<Double> a, List<Double> b) {
        if (a.size() < 2 || b.size() < 2) {
            return Double.NaN;
        }
        double dx = a.get(0) - b.get(0);
        double dy = a.get(1) - b.get(1);
        return Math.sqrt(dx * dx + dy * dy);
    }

    protected double bboxDistance(List<Double> a, List<Double> b) {
        if (a.size() < 4 || b.size() < 4) {
            return Double.NaN;
        }
        double dx = Math.max(0.0d, Math.max(a.get(0) - b.get(2), b.get(0) - a.get(2)));
        double dy = Math.max(0.0d, Math.max(a.get(1) - b.get(3), b.get(1) - a.get(3)));
        return Math.sqrt(dx * dx + dy * dy);
    }

    protected double normalizeLength(double value, Double requiredValue) {
        if (requiredValue != null && Math.abs(requiredValue) <= 100.0d && Math.abs(value) > 100.0d) {
            return value / 1000.0d;
        }
        return value;
    }

    protected double normalizeArea(double value, Double requiredValue) {
        if (requiredValue != null && Math.abs(requiredValue) <= 10_000.0d && Math.abs(value) > 100_000.0d) {
            return value / 1_000_000.0d;
        }
        return value;
    }

    protected List<String> normalizationNotes(double raw, Double requiredValue, String source) {
        if (requiredValue != null && Math.abs(requiredValue) <= 100.0d && Math.abs(raw) > 100.0d) {
            return List.of(source + " 按毫米制图单位折算为米");
        }
        return List.of();
    }

    protected List<String> collectNotes(List<MeasuredCandidate> candidates) {
        return candidates.stream()
                .flatMap(candidate -> candidate.notes().stream())
                .distinct()
                .toList();
    }

    protected ObjectNode baseDetails(CadMetricExecutionContext context, int candidateCount) {
        ObjectNode details = context.objectMapper().createObjectNode();
        details.put("candidateCount", candidateCount);
        return details;
    }

    protected List<MeasuredCandidate> sortedByValue(List<MeasuredCandidate> candidates, boolean ascending) {
        Comparator<MeasuredCandidate> comparator = Comparator.comparingDouble(MeasuredCandidate::value);
        if (!ascending) {
            comparator = comparator.reversed();
        }
        return candidates.stream().sorted(comparator).toList();
    }

    protected int evidenceLimit(CadMetricTarget target) {
        if (target == null || target.getMaxEvidence() == null || target.getMaxEvidence() <= 0) {
            return 8;
        }
        return target.getMaxEvidence();
    }

    protected List<Double> numbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Double> result = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text.replace(',', '.'));
        while (matcher.find()) {
            try {
                result.add(Double.parseDouble(matcher.group()));
            } catch (NumberFormatException ignored) {
                // ignore malformed number-like text
            }
        }
        return result;
    }

    protected String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private boolean matchesType(JsonNode node, List<String> types) {
        if (types == null || types.isEmpty()) {
            return true;
        }
        String actual = normalize(node.path("type").asText(""));
        if (actual.isEmpty()) {
            return true;
        }
        for (String type : types) {
            String expected = normalize(type);
            if (!expected.isEmpty() && actual.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private void addAll(List<String> result, List<String> values) {
        if (values != null) {
            result.addAll(values);
        }
    }

    private void collectNumbersFromMatchedTexts(
            List<JsonNode> nodes,
            CadMetricTarget target,
            Double requiredValue,
            List<MeasuredCandidate> candidates) {
        for (JsonNode node : nodes) {
            if (!matches(node, target)) {
                continue;
            }
            for (double value : numbers(node.path("text").asText(""))) {
                candidates.add(new MeasuredCandidate(
                        normalizeLength(value, requiredValue),
                        node,
                        normalizationNotes(value, requiredValue, "text")));
            }
        }
    }

    private void collectNearbyNumbers(
            List<JsonNode> texts,
            CadMetricTarget target,
            Double requiredValue,
            List<MeasuredCandidate> candidates) {
        for (JsonNode label : texts) {
            if (!matches(label, target) || !numbers(label.path("text").asText("")).isEmpty()) {
                continue;
            }
            List<Double> labelPoint = point(label);
            if (labelPoint.size() < 2) {
                continue;
            }
            JsonNode best = null;
            double bestDy = Double.MAX_VALUE;
            double bestDx = Double.MAX_VALUE;
            for (JsonNode valueText : texts) {
                List<Double> candidatePoint = point(valueText);
                if (candidatePoint.size() < 2) {
                    continue;
                }
                List<Double> values = numbers(valueText.path("text").asText(""));
                if (values.isEmpty()) {
                    continue;
                }
                double dy = Math.abs(candidatePoint.get(1) - labelPoint.get(1));
                double dx = candidatePoint.get(0) - labelPoint.get(0);
                if (dy <= TEXT_ROW_Y_TOLERANCE
                        && dx >= 0.0d
                        && dx <= TEXT_ROW_MAX_X_DISTANCE
                        && (dy < bestDy || (Math.abs(dy - bestDy) <= 0.000001d && dx < bestDx))) {
                    best = valueText;
                    bestDy = dy;
                    bestDx = dx;
                }
            }
            if (best != null) {
                for (double value : numbers(best.path("text").asText(""))) {
                    candidates.add(new MeasuredCandidate(
                            normalizeLength(value, requiredValue),
                            best,
                            normalizationNotes(value, requiredValue, "nearby_text")));
                }
            }
        }
    }

    private void collectTextMatches(List<MeasuredCandidate> result, List<JsonNode> nodes, CadMetricTarget target) {
        for (JsonNode node : nodes) {
            if (matches(node, target) && !node.path("text").asText("").isBlank()) {
                result.add(new MeasuredCandidate(1.0d, node, List.of()));
            }
        }
    }

    private List<JsonNode> textEntities(JsonNode drawingIr) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode entity : iterable(drawingIr.path("entities"))) {
            if (!entity.path("text").asText("").isBlank()) {
                result.add(entity);
            }
        }
        return result;
    }

    private String compare(MetricStatus status, Double measured, Double required, MetricComparator comparator) {
        if (status == MetricStatus.NOT_FOUND || status == MetricStatus.ERROR || status == MetricStatus.PARTIAL) {
            return "INSUFFICIENT_EVIDENCE";
        }
        if (comparator == null) {
            return "NOT_APPLICABLE";
        }
        if (comparator == MetricComparator.EXISTS) {
            return measured != null && measured > 0.0d ? "PASS" : "FAIL";
        }
        if (measured == null || required == null) {
            return "NOT_COMPARABLE";
        }
        double epsilon = 0.000001d;
        boolean pass = switch (comparator) {
            case GREATER_THAN -> measured > required;
            case GREATER_OR_EQUAL -> measured + epsilon >= required;
            case LESS_THAN -> measured < required;
            case LESS_OR_EQUAL -> measured <= required + epsilon;
            case EQUAL -> Math.abs(measured - required) <= epsilon;
            case EXISTS -> measured > 0.0d;
        };
        return pass ? "PASS" : "FAIL";
    }

    private double confidence(MetricStatus status, List<EvidenceRef> evidenceRefs) {
        if (status == MetricStatus.FOUND && evidenceRefs != null && !evidenceRefs.isEmpty()) {
            return 0.86d;
        }
        if (status == MetricStatus.PARTIAL) {
            return 0.55d;
        }
        return 0.2d;
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

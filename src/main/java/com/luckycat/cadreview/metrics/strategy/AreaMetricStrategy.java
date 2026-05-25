package com.luckycat.cadreview.metrics.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.metrics.CadMetricRequest;
import com.luckycat.cadreview.metrics.CadMetricResult;
import com.luckycat.cadreview.metrics.CadMetricTarget;
import com.luckycat.cadreview.metrics.MeasuredCandidate;
import com.luckycat.cadreview.metrics.MetricOperation;
import com.luckycat.cadreview.metrics.MetricStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AreaMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.MEASURE_AREA;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> strongCandidates = new ArrayList<>();
        List<MeasuredCandidate> weakGeometryCandidates = new ArrayList<>();
        for (JsonNode entity : iterable(context.drawingIr().path("entities"))) {
            if (!matches(entity, target)) {
                continue;
            }
            List<double[]> points = points(entity);
            if (points.size() >= 3 && entity.path("closed").asBoolean(false)) {
                double raw = polygonArea(points);
                MeasuredCandidate candidate = new MeasuredCandidate(
                        normalizeArea(raw, request.getRequiredValue()),
                        entity,
                        areaNotes(raw, request.getRequiredValue(), "closed_polyline_area"));
                if (hasAreaSemantic(entity)) {
                    strongCandidates.add(candidate);
                } else {
                    weakGeometryCandidates.add(withNote(candidate, "弱证据：闭合边界缺少明确防火分区/面积语义"));
                }
            } else {
                List<Double> bbox = bbox(entity);
                if (bbox.size() == 4) {
                    double raw = Math.abs(bbox.get(2) - bbox.get(0)) * Math.abs(bbox.get(3) - bbox.get(1));
                    if (raw > 0.0d) {
                        weakGeometryCandidates.add(new MeasuredCandidate(
                                normalizeArea(raw, request.getRequiredValue()),
                                entity,
                                withExtra(areaNotes(raw, request.getRequiredValue(), "bbox_area"),
                                        "弱证据：bbox 面积不能直接等同于防火分区/建筑面积")));
                    }
                }
            }
        }
        for (MeasuredCandidate candidate : numericTextCandidates(context.drawingIr(), target, null)) {
            strongCandidates.add(new MeasuredCandidate(
                    normalizeArea(candidate.value(), request.getRequiredValue()),
                    candidate.evidence(),
                    candidate.notes()));
        }
        if (strongCandidates.isEmpty() && weakGeometryCandidates.isEmpty()) {
            return notFound(request, context, "未找到闭合边界、面域 bbox 或面积文本");
        }
        if (strongCandidates.isEmpty()) {
            List<MeasuredCandidate> weakSorted = sortedByValue(weakGeometryCandidates, false);
            ObjectNode details = baseDetails(context, weakGeometryCandidates.size());
            details.put("evidenceStrength", "WEAK_GEOMETRY");
            details.put("strongCandidateCount", 0);
            details.put("weakCandidateCount", weakGeometryCandidates.size());
            details.put("maxArea", weakSorted.get(0).value());
            details.put("minArea", weakSorted.get(weakSorted.size() - 1).value());
            return result(
                    request,
                    MetricStatus.PARTIAL,
                    null,
                    evidenceRefs(weakSorted, evidenceLimit(target)),
                    collectNotes(weakSorted),
                    details);
        }
        List<MeasuredCandidate> sorted = sortedByValue(strongCandidates, false);
        ObjectNode details = baseDetails(context, strongCandidates.size() + weakGeometryCandidates.size());
        details.put("evidenceStrength", "SEMANTIC_BOUNDARY_OR_TEXT");
        details.put("strongCandidateCount", strongCandidates.size());
        details.put("weakCandidateCount", weakGeometryCandidates.size());
        details.put("maxArea", sorted.get(0).value());
        details.put("minArea", sorted.get(sorted.size() - 1).value());
        return result(
                request,
                MetricStatus.FOUND,
                sorted.get(0).value(),
                evidenceRefs(sorted, evidenceLimit(target)),
                collectNotes(sorted),
                details);
    }

    private double polygonArea(List<double[]> points) {
        double area = 0.0d;
        for (int i = 0; i < points.size(); i++) {
            double[] a = points.get(i);
            double[] b = points.get((i + 1) % points.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        return Math.abs(area) / 2.0d;
    }

    private List<String> areaNotes(double raw, Double requiredValue, String source) {
        if (requiredValue != null && Math.abs(requiredValue) <= 10_000.0d && Math.abs(raw) > 100_000.0d) {
            return List.of(source + " 按毫米制图单位折算为平方米");
        }
        return List.of(source);
    }

    private boolean hasAreaSemantic(JsonNode entity) {
        String haystack = normalize(String.join(" ",
                entity.path("layer").asText(""),
                entity.path("text").asText(""),
                entity.path("semantic").asText(""),
                entity.path("block").asText(""),
                entity.path("type").asText(""),
                entity.path("handle").asText("")));
        return haystack.contains("防火分区")
                || haystack.contains("防火")
                || haystack.contains("firezone")
                || haystack.contains("fire_zone")
                || haystack.contains("fire-zone")
                || haystack.contains("面积")
                || haystack.contains("area");
    }

    private MeasuredCandidate withNote(MeasuredCandidate candidate, String note) {
        return new MeasuredCandidate(candidate.value(), candidate.evidence(), withExtra(candidate.notes(), note));
    }

    private List<String> withExtra(List<String> notes, String note) {
        List<String> result = new ArrayList<>(notes == null ? List.of() : notes);
        result.add(note);
        return result;
    }
}

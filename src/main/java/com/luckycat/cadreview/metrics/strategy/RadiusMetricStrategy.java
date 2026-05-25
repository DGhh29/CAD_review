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
public class RadiusMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.MEASURE_RADIUS;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> weakGeometryCandidates = new ArrayList<>();
        for (JsonNode entity : iterable(context.drawingIr().path("entities"))) {
            if (!matches(entity, target) || !entity.path("radius").isNumber()) {
                continue;
            }
            String type = normalize(entity.path("type").asText(""));
            if ("arc".equals(type) || "circle".equals(type)) {
                double raw = entity.path("radius").asDouble();
                weakGeometryCandidates.add(new MeasuredCandidate(
                        normalizeLength(raw, request.getRequiredValue()),
                        entity,
                        withNote(normalizationNotes(raw, request.getRequiredValue(), "entity_radius"),
                                "弱证据：仅发现圆弧/圆半径，尚未确认属于道路转弯半径")));
            }
        }
        List<MeasuredCandidate> strongCandidates = new ArrayList<>();
        strongCandidates.addAll(dimensionMeasurements(context.drawingIr(), target, request.getRequiredValue()));
        strongCandidates.addAll(numericTextCandidates(context.drawingIr(), target, request.getRequiredValue()));
        if (strongCandidates.isEmpty() && weakGeometryCandidates.isEmpty()) {
            return notFound(request, context, "未找到圆弧、圆或 R= 半径证据");
        }
        if (strongCandidates.isEmpty()) {
            List<MeasuredCandidate> weakSorted = sortedByValue(weakGeometryCandidates, true);
            ObjectNode details = baseDetails(context, weakGeometryCandidates.size());
            details.put("evidenceStrength", "WEAK_GEOMETRY");
            details.put("strongCandidateCount", 0);
            details.put("weakCandidateCount", weakGeometryCandidates.size());
            details.put("minRadius", weakSorted.get(0).value());
            details.put("maxRadius", weakSorted.get(weakSorted.size() - 1).value());
            return result(
                    request,
                    MetricStatus.PARTIAL,
                    null,
                    evidenceRefs(weakSorted, evidenceLimit(target)),
                    collectNotes(weakSorted),
                    details);
        }
        List<MeasuredCandidate> sorted = sortedByValue(strongCandidates, true);
        ObjectNode details = baseDetails(context, strongCandidates.size() + weakGeometryCandidates.size());
        details.put("evidenceStrength", "DIRECT_DIMENSION_OR_TEXT");
        details.put("strongCandidateCount", strongCandidates.size());
        details.put("weakCandidateCount", weakGeometryCandidates.size());
        details.put("minRadius", sorted.get(0).value());
        details.put("maxRadius", sorted.get(sorted.size() - 1).value());
        return result(
                request,
                MetricStatus.FOUND,
                sorted.get(0).value(),
                evidenceRefs(sorted, evidenceLimit(target)),
                collectNotes(sorted),
                details);
    }

    private List<String> withNote(List<String> notes, String note) {
        List<String> result = new ArrayList<>(notes == null ? List.of() : notes);
        result.add(note);
        return result;
    }
}

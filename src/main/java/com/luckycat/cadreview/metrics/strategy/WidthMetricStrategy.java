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
public class WidthMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.MEASURE_WIDTH;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> strongCandidates = new ArrayList<>(dimensionMeasurements(
                context.drawingIr(), target, request.getRequiredValue()));
        strongCandidates.addAll(numericTextCandidates(context.drawingIr(), target, request.getRequiredValue()));
        List<MeasuredCandidate> weakGeometryCandidates = new ArrayList<>();
        for (JsonNode entity : iterable(context.drawingIr().path("entities"))) {
            if (!matches(entity, target)) {
                continue;
            }
            if (entity.path("length").isNumber()) {
                double raw = entity.path("length").asDouble();
                weakGeometryCandidates.add(new MeasuredCandidate(
                        normalizeLength(raw, request.getRequiredValue()),
                        entity,
                        List.of("弱证据：线性实体长度不能直接等同于道路/疏散净宽")));
            }
            List<Double> bbox = bbox(entity);
            if (bbox.size() == 4) {
                double raw = Math.min(Math.abs(bbox.get(2) - bbox.get(0)), Math.abs(bbox.get(3) - bbox.get(1)));
                if (raw > 0.0d) {
                    weakGeometryCandidates.add(new MeasuredCandidate(
                            normalizeLength(raw, request.getRequiredValue()),
                            entity,
                            List.of("弱证据：bbox 短边不能直接等同于道路/疏散净宽")));
                }
            }
        }
        if (strongCandidates.isEmpty() && weakGeometryCandidates.isEmpty()) {
            return notFound(request, context, "未找到宽度尺寸、宽度文字或可用线性实体");
        }
        if (strongCandidates.isEmpty()) {
            List<MeasuredCandidate> weakSorted = sortedByValue(weakGeometryCandidates, true);
            ObjectNode details = baseDetails(context, weakGeometryCandidates.size());
            details.put("evidenceStrength", "WEAK_GEOMETRY");
            details.put("strongCandidateCount", 0);
            details.put("weakCandidateCount", weakGeometryCandidates.size());
            details.put("minWidth", weakSorted.get(0).value());
            details.put("maxWidth", weakSorted.get(weakSorted.size() - 1).value());
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
        details.put("minWidth", sorted.get(0).value());
        details.put("maxWidth", sorted.get(sorted.size() - 1).value());
        return result(
                request,
                MetricStatus.FOUND,
                sorted.get(0).value(),
                evidenceRefs(sorted, evidenceLimit(target)),
                collectNotes(sorted),
                details);
    }
}

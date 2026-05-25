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
public class DistanceMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.MEASURE_DISTANCE;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> dimensionCandidates = dimensionMeasurements(
                context.drawingIr(), target, request.getRequiredValue());
        if (!dimensionCandidates.isEmpty()) {
            List<MeasuredCandidate> sorted = sortedByValue(dimensionCandidates, true);
            ObjectNode details = baseDetails(context, dimensionCandidates.size());
            details.put("source", "dimension_measurement");
            return result(
                    request,
                    MetricStatus.FOUND,
                    sorted.get(0).value(),
                    evidenceRefs(sorted, evidenceLimit(target)),
                    collectNotes(sorted),
                    details);
        }

        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode entity : iterable(context.drawingIr().path("entities"))) {
            if (matches(entity, target)) {
                nodes.add(entity);
            }
        }
        if (nodes.size() < 2) {
            return notFound(request, context, "至少需要两个匹配对象才能计算距离");
        }
        double bestDistance = Double.MAX_VALUE;
        JsonNode bestA = null;
        JsonNode bestB = null;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                double distance = bboxDistance(bbox(nodes.get(i)), bbox(nodes.get(j)));
                if (Double.isNaN(distance)) {
                    distance = pointDistance(point(nodes.get(i)), point(nodes.get(j)));
                }
                if (!Double.isNaN(distance) && distance < bestDistance) {
                    bestDistance = distance;
                    bestA = nodes.get(i);
                    bestB = nodes.get(j);
                }
            }
        }
        if (bestA == null || bestB == null) {
            return notFound(request, context, "匹配对象缺少 bbox 或点位，无法计算距离");
        }
        double measured = normalizeLength(bestDistance, request.getRequiredValue());
        List<MeasuredCandidate> evidence = List.of(
                new MeasuredCandidate(measured, bestA, normalizationNotes(bestDistance, request.getRequiredValue(), "geometry_distance")),
                new MeasuredCandidate(measured, bestB, normalizationNotes(bestDistance, request.getRequiredValue(), "geometry_distance"))
        );
        ObjectNode details = baseDetails(context, nodes.size());
        details.put("source", "geometry_distance");
        details.put("rawDistance", bestDistance);
        return result(
                request,
                MetricStatus.FOUND,
                measured,
                evidenceRefs(evidence, evidenceLimit(target)),
                collectNotes(evidence),
                details);
    }
}

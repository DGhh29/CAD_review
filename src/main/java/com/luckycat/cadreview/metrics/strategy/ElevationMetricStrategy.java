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
public class ElevationMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.MEASURE_ELEVATION_DIFF;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> elevations = new ArrayList<>(numericTextCandidates(context.drawingIr(), target, null));
        for (JsonNode entity : iterable(context.drawingIr().path("entities"))) {
            if (!matches(entity, target)) {
                continue;
            }
            addZCandidate(elevations, entity.path("point"), entity);
            addZCandidate(elevations, entity.path("insert"), entity);
            addZCandidate(elevations, entity.path("center"), entity);
        }
        if (elevations.size() < 2) {
            return notFound(request, context, "至少需要两个标高值才能计算高差");
        }
        List<MeasuredCandidate> sorted = sortedByValue(elevations, true);
        double diff = sorted.get(sorted.size() - 1).value() - sorted.get(0).value();
        ObjectNode details = baseDetails(context, elevations.size());
        details.put("minElevation", sorted.get(0).value());
        details.put("maxElevation", sorted.get(sorted.size() - 1).value());
        return result(
                request,
                MetricStatus.FOUND,
                diff,
                evidenceRefs(List.of(sorted.get(0), sorted.get(sorted.size() - 1)), evidenceLimit(target)),
                collectNotes(sorted),
                details);
    }

    private void addZCandidate(List<MeasuredCandidate> elevations, JsonNode point, JsonNode evidence) {
        if (point.isArray() && point.size() >= 3 && point.get(2).isNumber()) {
            elevations.add(new MeasuredCandidate(point.get(2).asDouble(), evidence, List.of("使用点位 Z 值作为标高候选")));
        }
    }
}

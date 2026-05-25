package com.luckycat.cadreview.metrics.strategy;

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
public class DimensionMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.EXTRACT_DIMENSION;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> candidates = new ArrayList<>(dimensionMeasurements(
                context.drawingIr(), target, request.getRequiredValue()));
        candidates.addAll(numericTextCandidates(context.drawingIr(), target, request.getRequiredValue()));
        if (candidates.isEmpty()) {
            return notFound(request, context, "未找到匹配尺寸或数值文本");
        }
        List<MeasuredCandidate> sorted = sortedByValue(candidates, true);
        MeasuredCandidate selected = sorted.get(0);
        ObjectNode details = baseDetails(context, candidates.size());
        details.put("minValue", sorted.get(0).value());
        details.put("maxValue", sorted.get(sorted.size() - 1).value());
        return result(
                request,
                MetricStatus.FOUND,
                selected.value(),
                evidenceRefs(sorted, evidenceLimit(target)),
                collectNotes(sorted),
                details);
    }
}

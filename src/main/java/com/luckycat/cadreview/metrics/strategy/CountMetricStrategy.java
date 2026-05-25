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
import java.util.Comparator;
import java.util.List;

@Component
public class CountMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.COUNT_OBJECTS;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> numericCounts = numericTextCandidates(context.drawingIr(), target, null).stream()
                .filter(candidate -> candidate.value() >= 0.0d)
                .sorted(Comparator.comparingDouble(MeasuredCandidate::value).reversed())
                .toList();
        if (!numericCounts.isEmpty()) {
            ObjectNode details = baseDetails(context, numericCounts.size());
            details.put("source", "text_or_indicator_number");
            return result(
                    request,
                    MetricStatus.FOUND,
                    numericCounts.get(0).value(),
                    evidenceRefs(numericCounts, evidenceLimit(target)),
                    collectNotes(numericCounts),
                    details);
        }

        List<MeasuredCandidate> matches = new ArrayList<>();
        collectMatches(matches, context.drawingIr().path("entities"), target);
        collectMatches(matches, context.drawingIr().path("blocks"), target);
        collectMatches(matches, context.drawingIr().path("texts"), target);
        if (matches.isEmpty()) {
            return notFound(request, context, "未找到匹配对象或数量文本");
        }
        ObjectNode details = baseDetails(context, matches.size());
        details.put("source", "matched_object_count");
        return result(
                request,
                MetricStatus.FOUND,
                (double) matches.size(),
                evidenceRefs(matches, evidenceLimit(target)),
                collectNotes(matches),
                details);
    }

    private void collectMatches(List<MeasuredCandidate> matches, JsonNode nodes, CadMetricTarget target) {
        for (JsonNode node : iterable(nodes)) {
            if (matches(node, target)) {
                matches.add(new MeasuredCandidate(1.0d, node, List.of()));
            }
        }
    }
}

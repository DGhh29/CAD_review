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
public class RatioMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.COMPUTE_RATIO;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        ValueEvidence numerator = valueFromRequestOrTarget(
                request.getNumeratorRequestId(), request.getNumeratorTarget(), context, true);
        ValueEvidence denominator = valueFromRequestOrTarget(
                request.getDenominatorRequestId(), request.getDenominatorTarget(), context,
                !looksLikeAreaTarget(request.getDenominatorTarget()));
        if (numerator.value() == null || denominator.value() == null || denominator.value() == 0.0d) {
            ObjectNode details = baseDetails(context, 0);
            details.put("hasNumerator", numerator.value() != null);
            details.put("hasDenominator", denominator.value() != null);
            List<MeasuredCandidate> evidence = new ArrayList<>();
            evidence.addAll(numerator.evidence());
            evidence.addAll(denominator.evidence());
            return result(
                    request,
                    MetricStatus.PARTIAL,
                    null,
                    evidenceRefs(evidence, evidenceLimit(target(request))),
                    List.of("分子或分母证据不足，无法计算比率"),
                    details);
        }
        double denominatorMultiplier = request.getDenominatorMultiplier() == null
                ? 1.0d
                : request.getDenominatorMultiplier();
        double ratio = numerator.value() / (denominator.value() * denominatorMultiplier);
        List<MeasuredCandidate> evidence = new ArrayList<>();
        evidence.addAll(numerator.evidence());
        evidence.addAll(denominator.evidence());
        ObjectNode details = baseDetails(context, evidence.size());
        details.put("numerator", numerator.value());
        details.put("denominator", denominator.value());
        details.put("denominatorMultiplier", denominatorMultiplier);
        return result(
                request,
                MetricStatus.FOUND,
                ratio,
                evidenceRefs(evidence, evidenceLimit(target(request))),
                collectNotes(evidence),
                details);
    }

    private ValueEvidence valueFromRequestOrTarget(
            String requestId,
            CadMetricTarget target,
            CadMetricExecutionContext context,
            boolean countFallback) {
        if (requestId != null && context.previousResults().containsKey(requestId)) {
            CadMetricResult result = context.previousResults().get(requestId);
            if (result.getMeasuredValue() != null) {
                return new ValueEvidence(result.getMeasuredValue(), List.of());
            }
        }
        CadMetricTarget actual = target == null ? new CadMetricTarget() : target;
        List<MeasuredCandidate> numeric = numericTextCandidates(context.drawingIr(), actual, null).stream()
                .filter(candidate -> candidate.value() >= 0.0d)
                .sorted(Comparator.comparingDouble(MeasuredCandidate::value).reversed())
                .toList();
        if (!numeric.isEmpty()) {
            return new ValueEvidence(numeric.get(0).value(), numeric);
        }
        if (!countFallback) {
            return new ValueEvidence(null, List.of());
        }
        List<MeasuredCandidate> matches = new ArrayList<>();
        collectMatches(matches, context.drawingIr().path("entities"), actual);
        collectMatches(matches, context.drawingIr().path("blocks"), actual);
        if (!matches.isEmpty()) {
            return new ValueEvidence((double) matches.size(), matches);
        }
        return new ValueEvidence(null, List.of());
    }

    private void collectMatches(List<MeasuredCandidate> matches, JsonNode nodes, CadMetricTarget target) {
        for (JsonNode node : iterable(nodes)) {
            if (matches(node, target)) {
                matches.add(new MeasuredCandidate(1.0d, node, List.of()));
            }
        }
    }

    private boolean looksLikeAreaTarget(CadMetricTarget target) {
        if (target == null) {
            return false;
        }
        String joined = String.join(" ",
                target.getLayerHints() == null ? List.of() : target.getLayerHints())
                + " "
                + String.join(" ", target.getTextHints() == null ? List.of() : target.getTextHints());
        String normalized = normalize(joined);
        return normalized.contains("面积") || normalized.contains("area");
    }

    private record ValueEvidence(Double value, List<MeasuredCandidate> evidence) {
    }
}

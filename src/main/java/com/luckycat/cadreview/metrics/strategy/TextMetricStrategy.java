package com.luckycat.cadreview.metrics.strategy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.metrics.CadMetricRequest;
import com.luckycat.cadreview.metrics.CadMetricResult;
import com.luckycat.cadreview.metrics.CadMetricTarget;
import com.luckycat.cadreview.metrics.MeasuredCandidate;
import com.luckycat.cadreview.metrics.MetricOperation;
import com.luckycat.cadreview.metrics.MetricStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TextMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.FIND_TEXT;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<MeasuredCandidate> matches = textMatches(context.drawingIr(), target);
        if (matches.isEmpty()) {
            return notFound(request, context, "未找到匹配文本证据");
        }
        ObjectNode details = baseDetails(context, matches.size());
        details.put("matchCount", matches.size());
        return result(
                request,
                MetricStatus.FOUND,
                (double) matches.size(),
                evidenceRefs(matches, evidenceLimit(target)),
                collectNotes(matches),
                details);
    }
}

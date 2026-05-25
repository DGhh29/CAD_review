package com.luckycat.cadreview.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * CAD 指标计算结果。Reviewer 只消费本结构，不直接自由调用几何工具。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CadMetricResult {

    private String requestId;
    private String ruleId;
    private String label;
    private MetricOperation operation;
    private MetricStatus status;
    private Double measuredValue;
    private Double requiredValue;
    private String unit;
    private String comparison;
    private Double confidence;

    @Builder.Default
    private List<EvidenceRef> evidenceRefs = new ArrayList<>();

    @Builder.Default
    private List<String> notes = new ArrayList<>();

    @Builder.Default
    private JsonNode details = NullNode.getInstance();
}

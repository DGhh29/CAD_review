package com.luckycat.cadreview.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 CAD 指标计算请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CadMetricRequest {

    private String requestId;
    private String ruleId;
    private String label;
    private MetricOperation operation;

    @Builder.Default
    private CadMetricTarget target = new CadMetricTarget();

    private Double requiredValue;
    private MetricComparator comparator;
    private String unit;

    private String numeratorRequestId;
    private String denominatorRequestId;
    private CadMetricTarget numeratorTarget;
    private CadMetricTarget denominatorTarget;

    @Builder.Default
    private Double denominatorMultiplier = 1.0d;
}

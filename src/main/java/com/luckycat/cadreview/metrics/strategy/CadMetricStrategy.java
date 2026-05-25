package com.luckycat.cadreview.metrics.strategy;

import com.luckycat.cadreview.metrics.CadMetricRequest;
import com.luckycat.cadreview.metrics.CadMetricResult;
import com.luckycat.cadreview.metrics.MetricOperation;

public interface CadMetricStrategy {

    MetricOperation operation();

    CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context);
}

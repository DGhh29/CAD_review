package com.luckycat.cadreview.metrics.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.metrics.CadMetricResult;

import java.util.Map;

public record CadMetricExecutionContext(
        JsonNode drawingIr,
        ObjectMapper objectMapper,
        Map<String, CadMetricResult> previousResults) {
}

package com.luckycat.cadreview.metrics;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record MeasuredCandidate(double value, JsonNode evidence, List<String> notes) {
}

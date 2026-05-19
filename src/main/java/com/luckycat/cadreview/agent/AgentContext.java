package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.luckycat.cadreview.dto.ReviewRule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    private String requestId;
    private long deadlineAt;
    private JsonNode drawingIr;
    private JsonNode irSummary;

    @Builder.Default
    private List<ReviewRule> rules = new ArrayList<>();
}

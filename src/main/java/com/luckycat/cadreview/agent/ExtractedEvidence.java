package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * EvidenceExtractorAgent 从单个 chunk 中抽取出来的可追溯证据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedEvidence {

    private String evidenceType;
    private String sourcePath;
    private String layer;
    private String entityType;
    private String blockName;
    private String content;
    private String matchedRequirement;
    private String reason;
    private Double confidence;

    @Builder.Default
    private List<Double> position = new ArrayList<>();

    @Builder.Default
    private List<Double> boundingBox = new ArrayList<>();

    @JsonProperty("priority_evidence")
    @Builder.Default
    private boolean priorityEvidence = true;
}

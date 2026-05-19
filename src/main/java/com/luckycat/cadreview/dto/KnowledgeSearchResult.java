package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchResult {
    private String id;
    private String content;
    private double score;
    private Map<String, Object> metadata;
}

package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchRequest {
    private String query;
    @Builder.Default
    private int topK = 5;
    private String category;
}

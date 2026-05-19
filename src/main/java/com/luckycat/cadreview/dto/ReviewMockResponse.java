package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ReviewMockResponse {
    private List<Finding> findings;
    private Provider provider;
    private String model;
    private ChatResponse.TokenUsage tokenUsage;
}

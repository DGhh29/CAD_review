package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatResponse {
    private String content;
    private Provider provider;
    private String model;
    private TokenUsage tokenUsage;

    @Data
    @Builder
    public static class TokenUsage {
        private Long promptTokens;
        private Long completionTokens;
    }
}

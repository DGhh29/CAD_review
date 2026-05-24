package com.luckycat.cadreview.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次模型输入的上下文预算记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextBudget {
    private AgentRole role;
    private String stage;
    private String taskId;
    private int maxChars;
    private int originalChars;
    private int finalChars;
    private int originalJsonBytes;
    private int finalJsonBytes;
    private int estimatedOriginalTokens;
    private int estimatedFinalTokens;
    private boolean overflow;
    private ContextOverflowPolicy overflowPolicy;

    @Builder.Default
    private Map<String, Integer> retainedCounts = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Integer> droppedCounts = new LinkedHashMap<>();
}

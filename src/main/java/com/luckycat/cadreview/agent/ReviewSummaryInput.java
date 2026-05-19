package com.luckycat.cadreview.agent;

import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.ChatResponse;
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
public class ReviewSummaryInput {

    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    @Builder.Default
    private List<ReviewTask> succeededTasks = new ArrayList<>();

    @Builder.Default
    private List<ReviewTask> failedTasks = new ArrayList<>();

    @Builder.Default
    private List<ReviewTask> skippedTasks = new ArrayList<>();

    @Builder.Default
    private List<String> skippedRuleIds = new ArrayList<>();

    private long durationMs;
    private String reason;
    private ChatResponse.TokenUsage totalTokens;
    private boolean partial;
}

package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Verdict;
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
public class ReviewReport {

    private String reportId;
    private Verdict overallVerdict;

    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    @Builder.Default
    private List<ConflictGroup> conflicts = new ArrayList<>();

    private ReviewCoverage coverage;

    @Builder.Default
    private List<String> failedTaskIds = new ArrayList<>();

    @Builder.Default
    private boolean partial = false;

    private long durationMs;
    private ChatResponse.TokenUsage totalTokens;
    private String reason;
}

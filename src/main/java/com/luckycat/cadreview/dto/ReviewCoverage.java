package com.luckycat.cadreview.dto;

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
public class ReviewCoverage {

    private int totalTasks;
    private int succeededTasks;
    private int failedTasks;
    private int skippedTasks;
    private int unanchoredCount;
    private double taskCoverageRate;
    private double unanchoredRate;

    @Builder.Default
    private List<String> failedTaskIds = new ArrayList<>();

    @Builder.Default
    private List<String> skippedTaskIds = new ArrayList<>();

    @Builder.Default
    private List<String> skippedRuleIds = new ArrayList<>();
}

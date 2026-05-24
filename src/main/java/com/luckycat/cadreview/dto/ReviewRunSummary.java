package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 前端轮询异步审图进度时看到的轻量摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRunSummary {
    private String runId;
    private ReviewRunStatus status;
    private String fileName;
    private String ruleSet;
    private int totalTasks;
    private int succeededTasks;
    private int failedTasks;
    private int skippedTasks;
    private String reason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
}

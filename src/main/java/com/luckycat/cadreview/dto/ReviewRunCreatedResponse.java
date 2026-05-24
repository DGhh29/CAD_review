package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建异步审图任务后的响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRunCreatedResponse {
    private String runId;
    private ReviewRunStatus status;
}

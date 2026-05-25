package com.luckycat.cadreview.dto;

/**
 * 异步审图子任务状态。
 */
public enum ReviewTaskStatus {
    DISPATCHED,
    RUNNING,
    REPAIRING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}

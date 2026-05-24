package com.luckycat.cadreview.dto;

/**
 * 异步审图运行状态。
 */
public enum ReviewRunStatus {
    UPLOADED,
    PARSED,
    CLEANED,
    RULE_PLANNED,
    DISPATCHED,
    REVIEWING,
    SUMMARIZING,
    COMPLETED,
    PARTIAL,
    FAILED
}

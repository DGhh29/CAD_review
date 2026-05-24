package com.luckycat.cadreview.agent;

/**
 * 上下文超长时的处理策略。
 */
public enum ContextOverflowPolicy {
    NONE,
    SHRINK_SUMMARY,
    SELECT_TOP_K_EVIDENCE,
    SPLIT_AND_REDUCE,
    UPGRADE_MODEL,
    PENDING_REVIEW
}

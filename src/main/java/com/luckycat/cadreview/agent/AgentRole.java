package com.luckycat.cadreview.agent;

/**
 * 多 Agent 评审流程中的角色枚举，用于在日志、监控指标、配置项里
 * 区分三个 Agent 的身份，便于按角色配置超时/重试/可观察性策略。
 *
 * <ul>
 *   <li>{@link #DISPATCHER} —— 任务分派者：把 IR 摘要 + 规则集喂给 LLM，拆出一组 ReviewTask。</li>
 *   <li>{@link #REVIEWER} —— 审核执行者：对单个 ReviewTask + 切好的相关 IR + 对应规则，调 LLM 产出 Finding 列表。</li>
 *   <li>{@link #SUMMARIZER} —— 汇总者：把所有 Finding 聚合、去重、检测冲突并生成最终 ReviewReport。</li>
 * </ul>
 */
public enum AgentRole {
    REGULATION_PLANNER,
    DISPATCHER,
    PRE_CLEANER,
    EVIDENCE_EXTRACTOR,
    REVIEWER,
    VERIFIER,
    SUMMARIZER
}

package com.luckycat.cadreview.dto.enums;

/**
 * 审核任务 / Finding 的风险等级枚举，用于驱动调度优先级与汇总策略。
 *
 * <p>由 DispatcherAgent 在拆任务时按规则重要性给到 ReviewTask.priority；
 * Reviewer 则按规则严重程度给到单条 Finding.riskLevel。
 *
 * <ul>
 *   <li>{@link #LOW} —— 低风险。一般是建议性条款 / 美观性问题，不影响合规结论；
 *       排队时排在最后，资源紧张时可被降级或跳过。</li>
 *   <li>{@link #MEDIUM} —— 中等风险。常规合规项，DispatcherAgent 给 ReviewTask 的默认值；
 *       Reviewer 调度时按正常队列处理。</li>
 *   <li>{@link #HIGH} —— 高风险。强制条款 / 安全相关。SummarizerAgent 在
 *       {@code applyVerification} 阶段会挑出"HIGH 且 confidence 低于
 *       {@code cad-review.agent.summarizer.verify-confidence-threshold}"的 Finding，
 *       触发二次 LLM 复核以提升结论可靠性。</li>
 * </ul>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

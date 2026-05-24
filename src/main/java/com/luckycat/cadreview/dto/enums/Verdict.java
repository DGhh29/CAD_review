package com.luckycat.cadreview.dto.enums;

/**
 * 单条 Finding 与整份 ReviewReport 的最终结论枚举。
 *
 * <p>三个值代表"通过 / 不通过 / 暂时下不了结论"。
 * 取值由 ReviewerAgent 给到 Finding 层面，再由 SummarizerAgent 在 {@code decideVerdict} 中
 * 聚合成 Report 层面的 overallVerdict。
 *
 * <ul>
 *   <li>{@link #PASS} —— 已审核通过。Reviewer 判定该规则在当前任务范围内未发现违规；
 *       Report 层只有"全部 Finding 都是 PASS、无冲突、无失败 / 跳过任务、未锚定率达标"才会聚合到 PASS。</li>
 *   <li>{@link #FAIL} —— 明确违反规范。Reviewer 给 FAIL 时强制要求 ruleId + evidenceText
 *       + entityId / boundingBox 至少一项作为锚点（见 ReviewerAgent 的硬约束），否则会被丢弃；
 *       Report 层只要存在 FAIL 且没有触发 PENDING_REVIEW 条件，就会聚合成 FAIL。</li>
 *   <li>{@link #PENDING_REVIEW} —— 需要人工复核。触发条件包括：
 *       Reviewer 自评 confidence 偏低、SummarizerAgent 检测到结论冲突、
 *       任务总数为 0 / 出现失败或跳过任务、未锚定证据比例超过
 *       {@code cad-review.agent.unanchored-pending-threshold}（默认 0.5），
 *       或者高风险但低置信度的 Finding 二次校验失败。</li>
 * </ul>
 */
public enum Verdict {
    PASS,
    FAIL,
    PENDING_REVIEW
}

package com.luckycat.cadreview.agent;

import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Summarizer 阶段的输入聚合体，由 {@link com.luckycat.cadreview.agent.AgentOrchestrator}
 * 在所有 Reviewer 收割完毕后填充，再交给 {@link SummarizerAgent#summarize} 生成最终报告。
 *
 * <p>把"评审过程中发生了什么"全部铺平到一个数据载体里：
 * <ul>
 *   <li>{@link #findings} —— Reviewer 输出的全部条目（无论 PASS/FAIL/PENDING）。</li>
 *   <li>{@link #succeededTasks} / {@link #failedTasks} / {@link #skippedTasks} ——
 *       任务三态划分；任意一类非空都会触发 {@code partial=true}。</li>
 *   <li>{@link #skippedRuleIds} —— 被跳过的规则 ID 集合，用于在覆盖率统计中精确列出"未覆盖规则"。</li>
 *   <li>{@link #reason} —— 上游已知的降级原因（如规则为空、Dispatcher 失败），Summarizer 会把它
 *       与自身检测到的问题拼接成最终 reason。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewSummaryInput {

    /** Reviewer 阶段产出的所有 Finding，未经去重/冲突检测。 */
    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    /** 成功跑完的 ReviewTask。 */
    @Builder.Default
    private List<ReviewTask> succeededTasks = new ArrayList<>();

    /** 因超时/异常/调度失败而未能产出有效 Finding 的 ReviewTask。 */
    @Builder.Default
    private List<ReviewTask> failedTasks = new ArrayList<>();

    /** 因任务数超过 maxReviewTasks 上限而被截断、未真正执行的 ReviewTask。 */
    @Builder.Default
    private List<ReviewTask> skippedTasks = new ArrayList<>();

    /** 与 skippedTasks 对应的规则 ID 去重集合，便于报告里直接展示"未覆盖规则"。 */
    @Builder.Default
    private List<String> skippedRuleIds = new ArrayList<>();

    /** 整次评审耗时（毫秒），由 Orchestrator 在 summarize 调用前测得。 */
    private long durationMs;

    /** 上游已知的降级原因，例如"未配置可用审核规则"。 */
    private String reason;

    /** 累计 Token 用量（可选，当前实现暂未在 Orchestrator 中填充）。 */
    private ChatResponse.TokenUsage totalTokens;

    /** 是否为降级/部分结果；只要存在 reason / failedTasks / skippedTasks 任一即为 true。 */
    private boolean partial;
}

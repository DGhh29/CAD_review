package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Verdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 多 Agent 评审流水线的最终响应体——一份图纸的完整审核报告。
 *
 * <p>{@code AgentOrchestrator.summarize} 是唯一的产出入口：
 * 无论中途解析失败 / Dispatcher 异常 / Reviewer 超时，都会走到这里产出"尽力而为"的报告，
 * 调用方只需关心返回值即可，不需要单独处理半路失败。
 *
 * <p>partial 字段用于明确告知前端"这是降级结果而非完整结果"，便于在 UI 上加提示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewReport {

    // 报告唯一 ID，由 SummarizerAgent 生成（通常是 UUID），用于审计回溯
    private String reportId;

    // 整体结论。决策见 SummarizerAgent.decideVerdict：
    // 任务总数=0 / 冲突非空 / 失败任务非空 / 跳过任务非空 / 未锚定率超阈值 / 含 PENDING_REVIEW Finding 均会强制降为 PENDING_REVIEW
    private Verdict overallVerdict;

    // 所有 Reviewer 产出的 Finding 汇总；@Builder.Default 保证未填时是空列表，前端可放心 forEach
    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    // SummarizerAgent 检测出的结论冲突分组；非空必然触发 overallVerdict = PENDING_REVIEW
    @Builder.Default
    private List<ConflictGroup> conflicts = new ArrayList<>();

    // 覆盖度统计（任务数、未锚定率、失败任务列表等），由 SummarizerAgent 一次性计算
    private ReviewCoverage coverage;

    // 失败任务 ID 的便捷副本。与 coverage.failedTaskIds 内容一致，
    // 在顶层冗余存放是为了前端可以不展开 coverage 也能拿到"挂掉了哪些任务"
    @Builder.Default
    private List<String> failedTaskIds = new ArrayList<>();

    // 是否为降级 / 部分结果。
    // AgentOrchestrator.summarize 只要带 reason、或失败任务非空、或跳过任务非空，就置为 true
    @Builder.Default
    private boolean partial = false;

    // 本次评审耗时（毫秒），从 executeReview 起点到 summarize 结束
    private long durationMs;

    // 整条链路（Dispatcher + 全部 Reviewer + 二次复核）累计的 token 用量；
    // 暂未在所有阶段都聚合，主要服务于后续成本核算
    private ChatResponse.TokenUsage totalTokens;

    // 降级原因 / 失败说明的人类可读文本，前端在 partial=true 时优先展示该字段
    private String reason;
}

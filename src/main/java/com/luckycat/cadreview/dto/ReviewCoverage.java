package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评审覆盖度统计——回答"这次评审覆盖到了什么、漏了什么"。
 *
 * <p>由 SummarizerAgent 在收尾阶段根据成功 / 失败 / 跳过的 ReviewTask、
 * 以及 Finding 中"既无 entityId 也无 boundingBox"的未锚定数量统一计算，
 * 最终挂在 {@code ReviewReport.coverage}。
 *
 * <p>这些数字是 {@code decideVerdict} 的关键输入：
 * 只要存在失败 / 跳过任务，或未锚定率超过 {@code unanchoredPendingThreshold}（默认 0.5），
 * 整体结论会被强制降为 PENDING_REVIEW。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewCoverage {

    // 本次评审拆出的总任务数 = succeededTasks + failedTasks + skippedTasks
    private int totalTasks;

    // 真正跑完并产出 Finding 的任务数
    private int succeededTasks;

    // 调度 / 执行失败的任务数（超时 / 异常 / 时间预算被 Summarizer 抢占的 cancel）
    private int failedTasks;

    // 因任务总数超过 maxReviewTasks 上限而未执行的任务数；
    // 见 AgentOrchestrator.limitTasks 的截断逻辑
    private int skippedTasks;

    // 未锚定的 Finding 数量：既没有 evidenceEntityIds 也没有 boundingBox 的结论；
    // 这类结论无法在图纸上定位，可信度低
    private int unanchoredCount;

    // 任务覆盖率 = succeededTasks / totalTasks，用于前端展示评审完成度
    private double taskCoverageRate;

    // 未锚定率 = unanchoredCount / findings.size()，超过阈值会触发整体 PENDING_REVIEW
    private double unanchoredRate;

    // 失败任务的 taskId 列表，用于在报告里展开"哪些任务跑挂了"
    @Builder.Default
    private List<String> failedTaskIds = new ArrayList<>();

    // 被截断跳过的任务 taskId 列表
    @Builder.Default
    private List<String> skippedTaskIds = new ArrayList<>();

    // 被跳过的任务里涉及到的规则 ID 集合（去重），方便用户一眼看出"哪些规则没审到"
    @Builder.Default
    private List<String> skippedRuleIds = new ArrayList<>();
}

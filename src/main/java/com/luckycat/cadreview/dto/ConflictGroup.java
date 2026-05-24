package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 结论冲突分组，承载 SummarizerAgent 检测到的"同一区域 + 同一条款下出现 PASS 与 FAIL 互斥结论"的场景。
 *
 * <p>由 {@code SummarizerAgent.detectConflicts} 产出：两两比较所有 Finding，
 * 当满足"条款 ID 相同 + 结论一 PASS 一 FAIL + 实体重叠 / boundingBox 重叠 / areaId 相同"任一条件时
 * 视为冲突，封装成本对象加入 {@code ReviewReport.conflicts}。
 *
 * <p>只要 ReviewReport 存在非空的 conflicts，{@code decideVerdict} 会强制把 overallVerdict
 * 降级为 PENDING_REVIEW（不允许带冲突直接结论 PASS / FAIL），同时这些冲突 Finding 也会被
 * 二次 LLM 校验流程 {@code applyVerification} 接管。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictGroup {

    // 冲突所在区域 ID，由 SummarizerAgent 通过 resolveConflictArea 取两条 Finding 共同 areaId 得到
    private String areaId;

    // 冲突涉及的规范条款 ID；冲突的前提就是两条 Finding 的 clauseId 相同
    private String clauseId;

    // 实际产生冲突的 Finding 列表，至少 2 条；用 @Builder.Default 保证未填时不会出现 null 而 NPE
    @Builder.Default
    private List<Finding> conflictingFindings = new ArrayList<>();

    // 人工或二次校验最终采纳的结论（即"裁决结果"）；
    // 当前实现里 Summarizer 只检测冲突不自动裁决，本字段保留给后续人工复核流程或更智能的二次仲裁填入
    private Finding resolvedFinding;
}

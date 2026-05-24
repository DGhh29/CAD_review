package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.dto.enums.Verdict;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条审核结论——评审流程中信息密度最高的核心 DTO。
 *
 * <p>由 ReviewerAgent 针对单个 ReviewTask + 单条规则产出，最终被聚合进 ReviewReport.findings。
 *
 * <p>ReviewerAgent 对 Finding 有一组硬约束（违反则被丢弃 / 抛错）：
 * <ul>
 *   <li>{@code areaId} 必须等于所属任务的 areaId —— 否则会破坏 SummarizerAgent 以
 *       (areaId + clauseId) 为主键的冲突检测。</li>
 *   <li>{@code ruleId} 必须落在所属任务 ruleIds 白名单内 —— 防 LLM 跨任务幻觉。</li>
 *   <li>当 {@code verdict == FAIL} 时，必须同时携带 ruleId + evidenceText
 *       + (evidenceEntityIds 非空 或 boundingBox 非空) 三类锚点。</li>
 * </ul>
 *
 * <p>另外一些字段会在 Reviewer 端被自动回填：layerNames 缺省继承任务、source 默认 "REVIEWER_AGENT"、
 * ruleVersion / clauseId 从规则定义里补齐——前端 / 调用方可放心读取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Finding {

    // 单条结论：PASS / FAIL / PENDING_REVIEW；为 FAIL 时强制要求锚点字段齐全
    private Verdict verdict;

    // 风险等级，决定是否触发 Summarizer 的二次 LLM 复核（HIGH + 低置信度才会触发）
    private RiskLevel riskLevel;

    // 给人看的中文判定理由；展示在前端报告里，建议简明引用规范条文
    private String reason;

    // 命中的规范条款 ID（如 "GB50016-3.4.5"），SummarizerAgent 以此 + areaId 作为冲突检测主键
    private String clauseId;

    // 证据涉及的 CAD 实体 ID 列表（解析后 IR 中的 entityId）；
    // FAIL 结论时本字段或 boundingBox 至少要有一项非空
    @Builder.Default
    private List<String> evidenceEntityIds = new ArrayList<>();

    // Reviewer 自评，取值 0.0~1.0；HIGH 风险且 confidence 低于阈值的 Finding
    // 会被 SummarizerAgent 拉去做二次 LLM 复核（详见 SummarizerAgent.needsVerification）
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;

    // 命中的规则 ID（关联 ReviewRule.id），必须在所属 ReviewTask.ruleIds 白名单内
    private String ruleId;

    // 规则的版本号，由 ReviewerAgent 从规则定义中回填，便于报告里追溯当时使用的规则版本
    private String ruleVersion;

    // 区域 ID，必须等于所属 ReviewTask.areaId；
    // 跨区域结论会破坏冲突检测主键，Reviewer 端会主动拒绝
    private String areaId;

    // 涉及的图层名列表；Reviewer 端缺省时会从所属 task 继承
    @Builder.Default
    private List<String> layerNames = new ArrayList<>();

    // 世界坐标系包围盒，约定顺序 [minX, minY, maxX, maxY]，前端用于在图纸上高亮证据；
    // FAIL 结论时本字段或 evidenceEntityIds 至少要有一项非空（ReviewerAgent 校验）
    private List<Double> boundingBox;

    // 引发该结论的图纸 / 标注原文，比如尺寸标注文本或注释字符串；FAIL 时必填
    private String evidenceText;

    // 来源标识，默认 "REVIEWER_AGENT"；保留给后续支持人工补录 / 其他来源时区分
    private String source;

    // 二次校验结果。仅在 SummarizerAgent.applyVerification 触发后才会有值；
    // 平时保持 null 表示该 Finding 未走二次复核
    private Verification verification;
}

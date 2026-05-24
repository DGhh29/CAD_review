package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Verdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Finding 的二次校验结果，挂在 {@link Finding#getVerification()} 上。
 *
 * <p>由 SummarizerAgent.applyVerification 在两类场景下产生：
 * <ul>
 *   <li>HIGH 风险且 confidence 低于 {@code summarizer.verifyConfidenceThreshold} 的 Finding —— 被挑出来复核；</li>
 *   <li>出现在 ConflictGroup.conflictingFindings 里的 Finding —— 因为冲突双方都要重新校验。</li>
 * </ul>
 *
 * <p>实现上，复核会用 {@code summarizer.verifyProvider} 指定的另一个供应商再调一次 LLM
 * （故意跨厂商互相校验降低单厂商幻觉风险）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Verification {

    // 校验状态，当前实现里只有两种取值："VERIFIED"（成功跑完）/ "FAILED"（LLM 调用或解析失败）
    private String status;

    // 实际执行校验的来源，格式："{Provider}:{model}"，如 "ANTHROPIC:claude-3-5-sonnet"；
    // 失败时只写 Provider 名
    private String verifiedBy;

    // 校验过程的备注。VERIFIED 时与 verifiedReason 同值；FAILED 时存放异常 message，便于排查
    private String comment;

    // 校验后给出的结论（仍是 PASS / FAIL / PENDING_REVIEW）；
    // 由 SummarizerAgent.validateVerificationOutput 强制要求非空，否则视为校验失败
    private Verdict verifiedVerdict;

    // 校验结论的解释文本，由复核 LLM 给出；前端可据此对比原始 reason 与复核 reason 的差异
    private String verifiedReason;
}

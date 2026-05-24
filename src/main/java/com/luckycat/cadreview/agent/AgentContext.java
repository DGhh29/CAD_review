package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.luckycat.cadreview.dto.ReviewRule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次评审请求在各 Agent 之间共享的上下文载体。
 *
 * <p>承载一次审核全流程所需的关键引用：原始 IR、给 Dispatcher 看的摘要视图、
 * 适用规则集合，以及全局截止时间 deadline。这些字段在 Dispatcher / Reviewer / Summarizer
 * 间按需读取，避免反复传递长参数列表。
 *
 * <p>当前实现里 {@link com.luckycat.cadreview.agent.AgentOrchestrator} 通过方法参数显式传递
 * 这些字段，保留此类作为未来重构入口（例如把请求级元数据统一收敛到一处时使用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {

    /** 请求 ID，用于日志/追踪定位整条评审链路。 */
    private String requestId;

    /** 全流程绝对截止时间戳（System.currentTimeMillis 同基准），各 Agent 据此动态压缩自身超时预算。 */
    private long deadlineAt;

    /** 解析器产出的完整图纸 IR；Reviewer 阶段会从中按 task 切片。 */
    private JsonNode drawingIr;

    /** 经 IrViewService.buildSummary 压缩后的摘要视图，专门喂给 Dispatcher，避免上下文爆炸。 */
    private JsonNode irSummary;

    /** 本次评审实际启用的规则集合（已按 ruleSet 过滤）。 */
    @Builder.Default
    private List<ReviewRule> rules = new ArrayList<>();
}

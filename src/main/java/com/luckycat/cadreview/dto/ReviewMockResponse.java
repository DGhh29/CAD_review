package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Mock 单包审核（{@code POST /api/ai/review-mock}）的响应体。
 *
 * <p>{@link com.luckycat.cadreview.service.ReviewMockService} 在一次 LLM 调用成功后填充本对象。
 * 与正式的 {@link ReviewReport} 不同，这里只携带原始 findings + 模型元信息，
 * 不做汇总、冲突检测、覆盖率统计——纯粹给前端联调用。
 */
@Data
@Builder
public class ReviewMockResponse {

    // LLM 直接产出的 Finding 列表（结构由 ReviewMockOutput schema 约束）
    private List<Finding> findings;

    // 实际命中的 LLM 供应商（可能由默认 provider 兜底，与请求里的 provider 不一定相同）
    private Provider provider;

    // 实际命中的模型名，例如 "gpt-4o-mini"
    private String model;

    // 本次调用的 token 用量，沿用与 ChatResponse 一致的计量类型，方便前端统一展示成本
    private ChatResponse.TokenUsage tokenUsage;
}

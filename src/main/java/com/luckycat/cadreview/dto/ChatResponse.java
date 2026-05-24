package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import lombok.Builder;
import lombok.Data;

/**
 * 通用对话响应体。
 *
 * <p>由 {@code ChatService} 包装 Spring AI 返回的结果而来，
 * 同时给前端暴露"内容 + 实际命中的 Provider / model + token 用量"四类信息，
 * 用于成本展示、A/B 对比与排查。
 *
 * <p>嵌套的 {@link TokenUsage} 也被 ReviewReport / ReviewMockResponse 复用，
 * 作为整套链路统一的 token 计量类型。
 */
@Data
@Builder
public class ChatResponse {

    // 模型回复的文本正文，原样透传，不做二次裁剪
    private String content;

    // 实际命中的 LLM 供应商；可能不同于请求里的 provider（被 default 兜底时）
    private Provider provider;

    // 实际命中的模型名称，例如 "gpt-4o-mini" / "claude-3-5-sonnet"
    private String model;

    // 本次调用的 token 用量
    private TokenUsage tokenUsage;

    /**
     * Token 用量计量。
     *
     * <p>用 Long 包装类型而非原始 long，以区分"未知 / 厂商未返回"与"确实为 0"两种语义。
     * 在 Spring AI 的 Usage 对象里也允许 null。
     */
    @Data
    @Builder
    public static class TokenUsage {
        // 输入侧 token 数（prompt + system 等），可能为 null
        private Long promptTokens;
        // 输出侧 token 数（模型生成的内容），可能为 null
        private Long completionTokens;
    }
}

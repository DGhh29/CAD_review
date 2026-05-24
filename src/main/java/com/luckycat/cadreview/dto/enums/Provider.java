package com.luckycat.cadreview.dto.enums;

/**
 * LLM 供应商枚举。决定走哪一套 ChatClient（带不同的 API Key、模型、结构化输出适配方式）。
 *
 * <p>在多个入口被使用：
 * <ul>
 *   <li>{@code ChatRequest.provider}、{@code AuditPackage.provider} —— 前端可显式指定，
 *       不传时由 {@code LlmProperties.defaultProvider} 兜底。</li>
 *   <li>{@code AgentProperties.summarizer.verifyProvider} —— Summarizer 二次复核的 Provider，
 *       通常会刻意配置成与 Reviewer 不同的厂商，互相校验降低单厂商幻觉风险。</li>
 * </ul>
 *
 * <ul>
 *   <li>{@link #OPENAI} —— 使用 {@code openAiReviewClient} / {@code openAiChatClient}，
 *       走 OpenAI 兼容协议。</li>
 *   <li>{@link #ANTHROPIC} —— 使用 {@code anthropicReviewClient} / {@code anthropicChatClient}，
 *       走 Claude 系列模型。</li>
 * </ul>
 */
public enum Provider {
    OPENAI,
    ANTHROPIC
}

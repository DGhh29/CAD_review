package com.luckycat.cadreview.config;

import com.luckycat.cadreview.dto.enums.Provider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM（大语言模型）调用相关的全局配置，对应 application.yml 的 {@code cad-review.llm.*} 段。
 *
 * <p>本类决定"在 OpenAI / Anthropic 两个 ChatClient 之间用哪一个"——
 * {@link com.luckycat.cadreview.agent.DispatcherAgent}、
 * {@link com.luckycat.cadreview.agent.ReviewerAgent}、
 * {@link com.luckycat.cadreview.service.ChatServiceImpl}
 * 都会读取 {@link #defaultProvider} 来选择实际注入的 ChatClient。
 *
 * <p>切换 provider 不需要改代码：只需在 application.yml 里把 {@code cad-review.llm.default-provider}
 * 从 OPENAI 改成 ANTHROPIC（或反之）即可，但要确认对应的 API key / base-url 已在 spring.ai.* 段配置好。
 *
 * <p>注意：{@link Summarizer 的 verifyProvider} 是单独配置的，与本默认 provider 解耦，
 * 这样可以做"OpenAI 主审 + Anthropic 复核"的交叉互验。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cad-review.llm")
public class LlmProperties {
    /** 默认 LLM 提供商。Dispatcher / Reviewer / 普通 Chat 流程在没有显式指定时使用本值。 */
    private Provider defaultProvider = Provider.OPENAI;

    /** 轻量模型配置：用于法规摘要、任务拆分、任务前置清洗等低成本环节。 */
    private ModelProfile lightweight = new ModelProfile(Provider.OPENAI, 12000);

    /** 深度模型配置：用于正式审核、冲突复核和最终总结等高风险环节。 */
    private ModelProfile deep = new ModelProfile(Provider.OPENAI, 32000);

    /** LLM 单次调用的传输层超时（毫秒）。注意这只是 HTTP 层的兜底，
     *  真正的业务超时由 AgentProperties.dispatcher/reviewer/summarizer.timeoutSeconds 控制。 */
    private int timeoutMs = 30000;

    /** 重试策略子配置。 */
    private Retry retry = new Retry();

    /**
     * LLM 调用的重试策略。
     * 当前仅作为"全局兜底"保留，Agent 各自的 maxAttempts 才是实际起作用的重试次数。
     */
    @Data
    public static class Retry {
        /** 最大重试次数（不含首发）。默认 1 即遇到瞬时错误时再试一次，避免雪崩式重试拖垮上游。 */
        private int maxAttempts = 1;

        /** 重试之间的退避间隔（毫秒）。 */
        private int backoffMs = 1000;
    }

    @Data
    public static class ModelProfile {
        /** 该模型档位使用的供应商。具体模型名仍由 spring.ai.*.chat.options.model 配置。 */
        private Provider provider = Provider.OPENAI;

        /** 该档位用于上下文预算估算的最大输入 token。 */
        private int maxInputTokens = 12000;

        public ModelProfile() {
        }

        public ModelProfile(Provider provider, int maxInputTokens) {
            this.provider = provider;
            this.maxInputTokens = maxInputTokens;
        }
    }
}

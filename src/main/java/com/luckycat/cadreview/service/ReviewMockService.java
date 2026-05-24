package com.luckycat.cadreview.service;

import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.AuditPackage;
import com.luckycat.cadreview.dto.ChatResponse;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewMockResponse;
import com.luckycat.cadreview.dto.enums.Provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Mock 版的"单包审核"服务，用于前端联调与 LLM 结构化输出能力验证。
 *
 * <p>与正式的多 Agent 流水线（{@link com.luckycat.cadreview.agent.AgentOrchestrator}）不同，
 * 本服务跳过了 CAD 解析与任务分派，直接拿前端塞进来的 {@link AuditPackage}
 * 让 LLM 输出一组 {@link Finding}。它存在的意义有两个：
 * <ul>
 *   <li>前端可以在没有真实 .dxf 的情况下走通 review 流程，演示报告结构</li>
 *   <li>验证当前 OpenAI / Anthropic 客户端的结构化输出兼容度</li>
 * </ul>
 *
 * <p>调用方：{@link com.luckycat.cadreview.controller.ReviewMockController}。
 *
 * <p>失败处理：内置重试（次数读 {@link LlmProperties.Retry#getMaxAttempts}），
 * 全部尝试都失败后抛 {@link StructuredOutputException}，
 * 由 {@link com.luckycat.cadreview.common.GlobalExceptionHandler#handleStructuredOutput}
 * 转成 HTTP 502 / 业务码 50201，并把 LLM 原始文本作为 data 字段回吐。
 */
@Slf4j
@Service
public class ReviewMockService {

    private final ChatClient openAiReviewClient;
    private final ChatClient anthropicReviewClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public ReviewMockService(
            @Qualifier("openAiReviewClient") ChatClient openAiReviewClient,
            @Qualifier("anthropicReviewClient") ChatClient anthropicReviewClient,
            LlmProperties properties,
            ObjectMapper objectMapper) {
        this.openAiReviewClient = openAiReviewClient;
        this.anthropicReviewClient = anthropicReviewClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 拿一份 {@link AuditPackage} 直接喂给 LLM，要求其按 {@link ReviewMockOutput} 结构返回 findings。
     *
     * <p>关键步骤：
     * <ol>
     *   <li>选 client（按入参 provider，缺省回退到默认 provider）</li>
     *   <li>用 {@link BeanOutputConverter} 生成 schema，拼到 system prompt 里</li>
     *   <li>把 AuditPackage 序列化成 JSON 当 user prompt</li>
     *   <li>循环重试 {@code maxAttempts} 次，每次拿 LLM 文本 → BeanOutputConverter 反序列化</li>
     * </ol>
     *
     * @throws IllegalArgumentException AuditPackage JSON 序列化失败（参数本身不合规）
     * @throws StructuredOutputException 重试用尽后仍无法解析为 {@link ReviewMockOutput}
     */
    public ReviewMockResponse review(AuditPackage auditPackage) {
        Provider resolved = auditPackage.getProvider() != null
                ? auditPackage.getProvider()
                : properties.getDefaultProvider();
        ChatClient client = resolved == Provider.ANTHROPIC ? anthropicReviewClient : openAiReviewClient;

        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        BeanOutputConverter<ReviewMockOutput> converter =
                new BeanOutputConverter<>(ReviewMockOutput.class);

        String auditJson;
        try {
            auditJson = objectMapper.writeValueAsString(auditPackage);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize AuditPackage", e);
        }

        String systemPrompt = "你是一个 CAD 图纸审核专家。根据提供的审核包，输出结构化审核结论。\n"
                + converter.getFormat();

        String userPrompt = "请审核以下审核包并输出 JSON 结论：\n" + auditJson;

        Exception lastError = null;
        String rawContent = null;
        ReviewMockOutput output = null;

        // 总尝试次数 = 配置的 maxAttempts + 首次调用，让 maxAttempts=0 也至少跑一遍
        int maxAttempts = properties.getRetry().getMaxAttempts() + 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                var aiResponse = client.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .chatResponse();

                rawContent = aiResponse.getResult().getOutput().getText();
                output = converter.convert(rawContent);

                long durationMs = System.currentTimeMillis() - start;
                var usage = aiResponse.getMetadata().getUsage();

                log.info("Review call requestId={} provider={} model={} durationMs={} "
                                + "promptTokens={} completionTokens={} structuredOutputSuccess={}",
                        requestId, resolved, aiResponse.getMetadata().getModel(),
                        durationMs, usage.getPromptTokens(), usage.getCompletionTokens(), true);

                return ReviewMockResponse.builder()
                        .findings(output.getFindings())
                        .provider(resolved)
                        .model(aiResponse.getMetadata().getModel())
                        .tokenUsage(ChatResponse.TokenUsage.builder()
                                .promptTokens(usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : null)
                                .completionTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : null)
                                .build())
                        .build();
            } catch (Exception e) {
                lastError = e;
                log.warn("Review attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }

        log.error("Review failed after {} attempts, requestId={} structuredOutputSuccess={}",
                maxAttempts, requestId, false);
        throw new StructuredOutputException(rawContent, lastError);
    }

    /**
     * LLM 结构化输出的目标 POJO：仅包含 findings 列表。
     * BeanOutputConverter 依据该类生成 JSON Schema 提示给 LLM。
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReviewMockOutput {
        private List<Finding> findings;
    }

    /**
     * 多次重试后仍无法把 LLM 文本解析为 {@link ReviewMockOutput} 时抛出的异常。
     *
     * <p>{@code rawContent} 字段保留最后一次 LLM 返回的原始文本，
     * 由 {@link com.luckycat.cadreview.common.GlobalExceptionHandler} 透传到响应 data，
     * 便于前端排查 / 提示用户重试。
     */
    public static class StructuredOutputException extends RuntimeException {
        @lombok.Getter
        private final String rawContent;

        public StructuredOutputException(String rawContent, Throwable cause) {
            super("Structured output parsing failed", cause);
            this.rawContent = rawContent;
        }
    }
}

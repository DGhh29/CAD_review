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

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReviewMockOutput {
        private List<Finding> findings;
    }

    public static class StructuredOutputException extends RuntimeException {
        @lombok.Getter
        private final String rawContent;

        public StructuredOutputException(String rawContent, Throwable cause) {
            super("Structured output parsing failed", cause);
            this.rawContent = rawContent;
        }
    }
}

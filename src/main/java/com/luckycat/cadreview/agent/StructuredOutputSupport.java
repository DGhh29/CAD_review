package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.dto.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Slf4j
@Component
public class StructuredOutputSupport {

    public StructuredOutputSupport(ObjectMapper objectMapper) {
    }

    public <T> StructuredResult<T> call(
            ChatClient client,
            String systemPrompt,
            String userPrompt,
            Class<T> outputType,
            int maxAttempts,
            String operation,
            Function<T, T> validator) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);
        String systemWithFormat = systemPrompt + "\n\n" + converter.getFormat();

        Exception lastError = null;
        String rawContent = null;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                var aiResponse = client.prompt()
                        .system(systemWithFormat)
                        .user(userPrompt)
                        .call()
                        .chatResponse();

                rawContent = aiResponse.getResult().getOutput().getText();
                T output = converter.convert(normalizeJson(rawContent));
                if (validator != null) {
                    output = validator.apply(output);
                }
                ChatResponse.TokenUsage usage = null;
                if (aiResponse.getMetadata() != null && aiResponse.getMetadata().getUsage() != null) {
                    var springUsage = aiResponse.getMetadata().getUsage();
                    usage = ChatResponse.TokenUsage.builder()
                            .promptTokens(springUsage.getPromptTokens() != null ? springUsage.getPromptTokens().longValue() : null)
                            .completionTokens(springUsage.getCompletionTokens() != null ? springUsage.getCompletionTokens().longValue() : null)
                            .build();
                }
                return new StructuredResult<>(
                        output,
                        rawContent,
                        aiResponse.getMetadata() != null ? aiResponse.getMetadata().getModel() : null,
                        usage);
            } catch (Exception ex) {
                lastError = ex;
                log.warn("{} attempt {} failed: {}", operation, attempt + 1, ex.getMessage());
            }
        }
        throw new StructuredOutputException(operation, rawContent, lastError);
    }

    private String normalizeJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Structured output is empty");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak >= 0) {
                trimmed = trimmed.substring(firstLineBreak + 1).trim();
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        int objectStart = indexOfJsonStart(trimmed);
        int objectEnd = indexOfJsonEnd(trimmed);
        if (objectStart >= 0 && objectEnd > objectStart) {
            trimmed = trimmed.substring(objectStart, objectEnd + 1);
        }
        return trimmed;
    }

    private int indexOfJsonStart(String text) {
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    private int indexOfJsonEnd(String text) {
        int objectEnd = text.lastIndexOf('}');
        int arrayEnd = text.lastIndexOf(']');
        return Math.max(objectEnd, arrayEnd);
    }

    @Data
    @AllArgsConstructor
    public static class StructuredResult<T> {
        private T output;
        private String rawContent;
        private String model;
        private ChatResponse.TokenUsage tokenUsage;
    }

    public static class StructuredOutputException extends RuntimeException {
        private final String operation;
        private final String rawContent;

        public StructuredOutputException(String operation, String rawContent, Throwable cause) {
            super(operation + " structured output parsing failed", cause);
            this.operation = operation;
            this.rawContent = rawContent;
        }

        public String getOperation() {
            return operation;
        }

        public String getRawContent() {
            return rawContent;
        }
    }
}

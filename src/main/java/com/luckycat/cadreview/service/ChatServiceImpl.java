package com.luckycat.cadreview.service;

import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.ChatResponse;
import com.luckycat.cadreview.dto.enums.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient openAiChatClient;
    private final ChatClient anthropicChatClient;
    private final LlmProperties properties;

    public ChatServiceImpl(
            @Qualifier("openAiChatClient") ChatClient openAiChatClient,
            @Qualifier("anthropicChatClient") ChatClient anthropicChatClient,
            LlmProperties properties) {
        this.openAiChatClient = openAiChatClient;
        this.anthropicChatClient = anthropicChatClient;
        this.properties = properties;
    }

    @Override
    public ChatResponse chat(Provider provider, String message) {
        Provider resolved = provider != null ? provider : properties.getDefaultProvider();
        ChatClient client = resolved == Provider.ANTHROPIC ? anthropicChatClient : openAiChatClient;

        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        org.springframework.ai.chat.model.ChatResponse aiResponse = client.prompt()
                .user(message)
                .call()
                .chatResponse();

        long durationMs = System.currentTimeMillis() - start;
        var usage = aiResponse.getMetadata().getUsage();

        log.info("LLM call requestId={} provider={} model={} durationMs={} promptTokens={} completionTokens={}",
                requestId, resolved, aiResponse.getMetadata().getModel(),
                durationMs, usage.getPromptTokens(), usage.getCompletionTokens());

        return ChatResponse.builder()
                .content(aiResponse.getResult().getOutput().getText())
                .provider(resolved)
                .model(aiResponse.getMetadata().getModel())
                .tokenUsage(ChatResponse.TokenUsage.builder()
                        .promptTokens(usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : null)
                        .completionTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : null)
                        .build())
                .build();
    }
}

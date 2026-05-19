package com.luckycat.cadreview.service;

import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.ChatResponse;
import com.luckycat.cadreview.dto.KnowledgeSearchResult;
import com.luckycat.cadreview.dto.enums.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient openAiChatClient;
    private final ChatClient anthropicChatClient;
    private final LlmProperties properties;
    private final KnowledgeService knowledgeService;

    public ChatServiceImpl(
            @Qualifier("openAiChatClient") ChatClient openAiChatClient,
            @Qualifier("anthropicChatClient") ChatClient anthropicChatClient,
            LlmProperties properties,
            KnowledgeService knowledgeService) {
        this.openAiChatClient = openAiChatClient;
        this.anthropicChatClient = anthropicChatClient;
        this.properties = properties;
        this.knowledgeService = knowledgeService;
    }

    @Override
    public ChatResponse chat(Provider provider, String message) {
        Provider resolved = provider != null ? provider : properties.getDefaultProvider();
        ChatClient client = resolved == Provider.ANTHROPIC ? anthropicChatClient : openAiChatClient;

        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        String systemContext = buildRagContext(message);

        org.springframework.ai.chat.model.ChatResponse aiResponse;
        if (systemContext.isEmpty()) {
            aiResponse = client.prompt()
                    .user(message)
                    .call()
                    .chatResponse();
        } else {
            aiResponse = client.prompt()
                    .system(systemContext)
                    .user(message)
                    .call()
                    .chatResponse();
        }

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

    private String buildRagContext(String message) {
        try {
            List<KnowledgeSearchResult> clauses = knowledgeService.searchRelevantClauses(message, 5);
            if (clauses.isEmpty()) {
                return "";
            }
            String context = clauses.stream()
                    .map(c -> String.format("[规范参考] 来源: %s | 内容: %s",
                            c.getMetadata().getOrDefault("fileName", "unknown"),
                            c.getContent()))
                    .collect(Collectors.joining("\n"));
            return "以下是与本次审查相关的规范条款，请在审查时参考:\n\n" + context;
        } catch (Exception e) {
            log.warn("RAG retrieval failed, proceeding without context: {}", e.getMessage());
            return "";
        }
    }
}

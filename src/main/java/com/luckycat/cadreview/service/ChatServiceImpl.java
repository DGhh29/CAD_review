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

/**
 * {@link ChatService} 的默认实现：带 RAG 增强的普通对话。
 *
 * <p>处理流程：
 * <ol>
 *   <li>按 {@code provider} 选 OpenAI 或 Anthropic 客户端</li>
 *   <li>调 {@link KnowledgeService#searchRelevantClauses} 在向量库中找相关规范条款，
 *       拼成 system context（找不到或失败则降级为无 RAG 直接对话）</li>
 *   <li>调 LLM 拿响应，记录 token 与耗时日志</li>
 * </ol>
 *
 * <p>调用方：{@link com.luckycat.cadreview.controller.ChatController}。
 * 与多 Agent 审图链路完全解耦，主要用于前端聊天面板的对话问答。
 */
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

    /**
     * 发起一次带 RAG 检索增强的对话。
     *
     * <p>失败模式：
     * <ul>
     *   <li>LLM 调用本身失败——异常向上抛，由
     *       {@link com.luckycat.cadreview.common.GlobalExceptionHandler#handleGeneral}
     *       兜底转 500</li>
     *   <li>RAG 检索失败——降级为不带 system 的纯对话，记 warn 日志</li>
     * </ul>
     */
    @Override
    public ChatResponse chat(Provider provider, String message) {
        Provider resolved = provider != null ? provider : properties.getDefaultProvider();
        ChatClient client = resolved == Provider.ANTHROPIC ? anthropicChatClient : openAiChatClient;

        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        String systemContext = buildRagContext(message);

        // 拆成两条分支是因为 system 为空时直接传空串会浪费 token，且不同 provider 行为不一致
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

    /**
     * 用用户问题去向量库里检索 top-5 条最相关的规范条款，拼成一段 system 提示。
     *
     * <p>检索失败（向量库不通、超时等）时返回空串，让上游降级到无 RAG 模式，
     * 避免知识库故障把整个聊天接口拖死。
     *
     * @return 拼装好的 system 文本；无可用条款或检索失败时返回空串
     */
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

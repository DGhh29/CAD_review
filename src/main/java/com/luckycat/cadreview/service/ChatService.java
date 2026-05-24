package com.luckycat.cadreview.service;

import com.luckycat.cadreview.dto.ChatResponse;
import com.luckycat.cadreview.dto.enums.Provider;

/**
 * 通用对话服务的契约。
 *
 * <p>对外暴露一次"普通聊天"调用：调用方传入 LLM 提供商与用户消息，
 * 实现负责选客户端、做 RAG 上下文拼接、调 LLM、统计 token 用量。
 *
 * <p>当前唯一实现是 {@link ChatServiceImpl}，由
 * {@link com.luckycat.cadreview.controller.ChatController} 调用，
 * 不参与 Agent 审图流水线，是给前端做问答 / 调试用的旁路通道。
 */
public interface ChatService {

    /**
     * 发起一次对话调用。
     *
     * @param provider 指定 LLM 提供商（OpenAI / Anthropic）。传 {@code null} 时按
     *                 {@code llm.default-provider} 配置选默认值。
     * @param message  用户消息，用作 user prompt
     * @return 包含 LLM 文本回复、所用模型、token 使用量的响应对象
     */
    ChatResponse chat(Provider provider, String message);
}

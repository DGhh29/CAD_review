package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通用对话请求体（{@code POST /api/chat}）。
 *
 * <p>承载前端给 ChatService 的一次单轮对话调用：携带可选的 LLM 供应商
 * 与必须存在的用户消息正文。Service 内部会按需走 RAG 检索（{@code KnowledgeService}）
 * 把召回到的规范片段注入到 system prompt，再调对应 ChatClient 产出回复。
 */
@Data
public class ChatRequest {

    // 指定走哪个 LLM 供应商；为空时由 LlmProperties.defaultProvider 决定
    private Provider provider;

    // 用户消息正文。@NotBlank 确保不为空、不全是空白字符——
    // 否则后续拼 prompt 会得到无意义的请求并白白消耗 token
    @NotBlank(message = "message must not be blank")
    private String message;
}

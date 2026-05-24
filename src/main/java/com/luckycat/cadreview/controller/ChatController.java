package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.ChatRequest;
import com.luckycat.cadreview.dto.ChatResponse;
import com.luckycat.cadreview.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用 LLM 对话 REST 入口,路径前缀 {@code /api/ai}。
 *
 * <p>提供与底层 LLM（OpenAI / Anthropic）的"裸"对话能力,不涉及任何 CAD 审核业务,
 * 主要给前端的测试面板、提示词调试工具,以及内部联调用——
 * 用来快速验证 LLM 连通性、Provider 配置和提示词效果。
 *
 * <p>真正的图纸审核入口在 {@code ReviewController},本控制器与多 Agent 审核流水线无直接关系。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 与 LLM 进行单轮对话:{@code POST /api/ai/chat}。
     *
     * <p>请求体字段:
     * <ul>
     *   <li>{@code provider}:可选,指定 LLM 供应商({@code OPENAI} / {@code ANTHROPIC});
     *       不传时由 {@link ChatService} 按全局默认 Provider 选择</li>
     *   <li>{@code message}:必填,用户输入文本</li>
     * </ul>
     *
     * <p>返回 {@link ChatResponse},包含模型回复内容、实际使用的 Provider / model 名、token 用量等元信息,
     * 便于前端展示成本与调用细节。
     *
     * @param request 已通过 {@code @Valid} 校验,message 不可为空白
     */
    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request.getProvider(), request.getMessage());
        return ApiResult.ok(response);
    }
}

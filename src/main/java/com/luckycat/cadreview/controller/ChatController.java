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

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request.getProvider(), request.getMessage());
        return ApiResult.ok(response);
    }
}

package com.luckycat.cadreview.config;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;

@Configuration
public class ChatClientConfig {

    @Bean
    public ToolCallback skillsTool(
            @Value("${cad-review.skills.paths}") List<Resource> skillPaths) {
        return SkillsTool.builder()
                .addSkillsResources(skillPaths)
                .build();
    }

    @Bean("openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel model, ToolCallback skillsTool) {
        return ChatClient.builder(model)
                .defaultToolCallbacks(skillsTool)
                .build();
    }

    @Bean("anthropicChatClient")
    public ChatClient anthropicChatClient(AnthropicChatModel model, ToolCallback skillsTool) {
        return ChatClient.builder(model)
                .defaultToolCallbacks(skillsTool)
                .build();
    }

    @Bean("openAiReviewClient")
    public ChatClient openAiReviewClient(OpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }

    @Bean("anthropicReviewClient")
    public ChatClient anthropicReviewClient(AnthropicChatModel model) {
        return ChatClient.builder(model).build();
    }
}

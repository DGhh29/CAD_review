package com.luckycat.cadreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration," +
                        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
class CadReviewApplicationTests {

    @TestConfiguration
    static class MockChatConfig {

        private static ChatModel stubChatModel() {
            ChatModel model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));
            return model;
        }

        @Bean
        @Primary
        org.springframework.ai.openai.OpenAiChatModel openAiChatModel() {
            return null;
        }

        @Bean
        org.springframework.ai.anthropic.AnthropicChatModel anthropicChatModel() {
            return null;
        }

        @Bean("openAiChatClient")
        @Primary
        ChatClient openAiChatClient() {
            return ChatClient.builder(stubChatModel()).build();
        }

        @Bean("anthropicChatClient")
        ChatClient anthropicChatClient() {
            return ChatClient.builder(stubChatModel()).build();
        }

        @Bean("openAiReviewClient")
        ChatClient openAiReviewClient() {
            return ChatClient.builder(stubChatModel()).build();
        }

        @Bean("anthropicReviewClient")
        ChatClient anthropicReviewClient() {
            return ChatClient.builder(stubChatModel()).build();
        }

        @Bean
        ToolCallback skillsTool(@org.springframework.beans.factory.annotation.Value(
                "${cad-review.skills.paths}") List<Resource> skillPaths) {
            return org.springaicommunity.agent.tools.SkillsTool.builder()
                    .addSkillsResources(skillPaths)
                    .build();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void contextLoads() {
    }
}

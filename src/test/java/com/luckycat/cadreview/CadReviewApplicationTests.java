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

/**
 * Spring Boot 上下文加载冒烟测试。
 *
 * <p>本测试只验证一件事：所有 Bean 能在测试环境下被装配起来、应用上下文可以正常启动。
 * 不做任何业务断言，{@link #contextLoads()} 为空方法是有意为之 —— Spring Boot 测试约定下，
 * 上下文启动失败本身就会让该测试失败。
 *
 * <p>为了避免在 CI 上真正打 OpenAI / Anthropic 接口，这里通过 {@code spring.autoconfigure.exclude}
 * 屏蔽了所有 Spring AI 的真实自动配置，并用 {@link MockChatConfig} 注入桩 ChatClient
 * 替换业务里被 {@code @Qualifier} 引用的那几个 Bean。
 */
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
                "spring.main.allow-bean-definition-overriding=true",
                "cad-review.review-run.initialize-schema=false"
        }
)
class CadReviewApplicationTests {

    /**
     * 测试专用的 Bean 配置：用 Mockito 桩对象替换所有真实的 ChatModel / ChatClient，
     * 让上下文启动时不会因为缺少 OpenAI / Anthropic 凭证而失败。
     */
    @TestConfiguration
    static class MockChatConfig {

        /** 构造一个最小可用的 ChatModel 桩，对任意 Prompt 调用返回 mock 的 ChatResponse。 */
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

        @Bean("lightweightReviewClient")
        ChatClient lightweightReviewClient() {
            return ChatClient.builder(stubChatModel()).build();
        }

        @Bean("deepReviewClient")
        ChatClient deepReviewClient() {
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

    /**
     * 应用上下文加载测试。
     * 方法体留空：只要 Spring 容器启动成功就算通过，启动失败会自动 fail。
     */
    @Test
    void contextLoads() {
    }
}

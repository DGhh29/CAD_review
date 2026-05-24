package com.luckycat.cadreview.integration;

import com.luckycat.cadreview.config.LlmProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实模型连通性测试。
 *
 * <p>默认不启用，避免普通单元测试因为外部模型服务 503、网络不可用或额度问题而失败。
 * 需要真实请求当前配置的模型时，运行时添加：
 *
 * <pre>
 * -Dcad-review.live-llm=true
 * </pre>
 */
@Tag("live")
@EnabledIfSystemProperty(named = "cad-review.live-llm", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=classpath:/application.yml",
        "spring.autoconfigure.exclude=",
        "cad-review.review-run.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.initialize-schema=false"
})
class LiveLlmConnectivityTest {

    @Qualifier("lightweightReviewClient")
    private final ChatClient lightweightReviewClient;

    @Qualifier("deepReviewClient")
    private final ChatClient deepReviewClient;

    private final LlmProperties llmProperties;
    private final String openAiBaseUrl;
    private final String openAiCompletionsPath;
    private final String openAiModel;

    @Autowired
    LiveLlmConnectivityTest(
            @Qualifier("lightweightReviewClient") ChatClient lightweightReviewClient,
            @Qualifier("deepReviewClient") ChatClient deepReviewClient,
            LlmProperties llmProperties,
            @Value("${spring.ai.openai.base-url:}") String openAiBaseUrl,
            @Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String openAiCompletionsPath,
            @Value("${spring.ai.openai.chat.options.model:}") String openAiModel) {
        this.lightweightReviewClient = lightweightReviewClient;
        this.deepReviewClient = deepReviewClient;
        this.llmProperties = llmProperties;
        this.openAiBaseUrl = openAiBaseUrl;
        this.openAiCompletionsPath = openAiCompletionsPath;
        this.openAiModel = openAiModel;
    }

    @Test
    void lightweightReviewClientShouldCallCurrentModelSuccessfully() {
        printResolvedOpenAiConfig("lightweightReviewClient");
        assertModelResponds(lightweightReviewClient, "lightweightReviewClient");
    }

    @Test
    void deepReviewClientShouldCallCurrentModelSuccessfully() {
        printResolvedOpenAiConfig("deepReviewClient");
        assertModelResponds(deepReviewClient, "deepReviewClient");
    }

    private void assertModelResponds(ChatClient client, String clientName) {
        ChatResponse response = client.prompt()
                .system("你是模型连通性测试。请严格只输出 OK 两个字母。")
                .user("请回复 OK")
                .call()
                .chatResponse();

        assertThat(response).as(clientName + " response").isNotNull();
        assertThat(response.getResult()).as(clientName + " result").isNotNull();
        assertThat(response.getResult().getOutput()).as(clientName + " output").isNotNull();

        String text = response.getResult().getOutput().getText();
        System.out.printf("%s model=%s response=%s%n",
                clientName,
                response.getMetadata() == null ? null : response.getMetadata().getModel(),
                text);

        assertThat(text).as(clientName + " text").isNotBlank();
    }

    private void printResolvedOpenAiConfig(String clientName) {
        System.out.printf(
                "%s defaultProvider=%s lightweightProvider=%s deepProvider=%s openAiModel=%s expectedOpenAiChatUrl=%s%n",
                clientName,
                llmProperties.getDefaultProvider(),
                llmProperties.getLightweight().getProvider(),
                llmProperties.getDeep().getProvider(),
                openAiModel,
                expectedOpenAiChatCompletionsUrl());
    }

    private String expectedOpenAiChatCompletionsUrl() {
        if (openAiBaseUrl == null || openAiBaseUrl.isBlank()) {
            return "";
        }
        String normalized = openAiBaseUrl.endsWith("/")
                ? openAiBaseUrl.substring(0, openAiBaseUrl.length() - 1)
                : openAiBaseUrl;
        String path = openAiCompletionsPath.startsWith("/")
                ? openAiCompletionsPath
                : "/" + openAiCompletionsPath;
        return normalized + path;
    }
}

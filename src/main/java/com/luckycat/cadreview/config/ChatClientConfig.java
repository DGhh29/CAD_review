package com.luckycat.cadreview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.dto.enums.Provider;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * Spring AI ChatClient 的装配中心。
 *
 * <p>本配置一次性产出 4 个 ChatClient Bean，按 {@code @Qualifier} 名称区分用途：
 * <ul>
 *   <li><b>openAiChatClient / anthropicChatClient</b> —— 给业务 Chat（{@link com.luckycat.cadreview.service.ChatServiceImpl}）使用，
 *       挂载了 {@code skillsTool}（cad-review.skills.paths 下的 SKILL.md），可以触发工具调用；</li>
 *   <li><b>openAiReviewClient / anthropicReviewClient</b> —— 给评审三件套
 *       （{@link com.luckycat.cadreview.agent.DispatcherAgent}、
 *       {@link com.luckycat.cadreview.agent.ReviewerAgent}、
 *       {@link com.luckycat.cadreview.agent.SummarizerAgent}）使用，
 *       <b>不</b>挂载 skillsTool，避免 Agent 在结构化输出过程中跑偏调用工具
 *       （Reviewer 必须严格输出 JSON 才能被 BeanOutputConverter 解析）。</li>
 * </ul>
 *
 * <p>底层 {@link OpenAiChatModel} / {@link AnthropicChatModel} 由 spring-ai-starter 自动装配，
 * 模型/温度/max-tokens/api-key/base-url 等参数都来自 application.yml 的 {@code spring.ai.openai.*}
 * 与 {@code spring.ai.anthropic.*}。
 *
 * <p>具体走 OpenAI 还是 Anthropic，运行期由 {@link LlmProperties#getDefaultProvider()} 选择，
 * 各 Agent 内部根据 provider 在两个 *ReviewClient 之间二选一。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 全局 ObjectMapper。
     * 仅在容器中尚未存在 ObjectMapper Bean 时注册（{@link ConditionalOnMissingBean}），
     * 避免与 Spring Boot Web 自动配置出来的 ObjectMapper 冲突。
     * 被 Agent 层（toJson/序列化 prompt 输入）以及 StructuredOutputSupport 使用。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * 把 {@code cad-review.skills.paths} 下的所有 SKILL.md 资源打包成一个 ToolCallback，
     * 让 LLM 能"按需调用"这些技能（例如查规范、查图层定义）。
     *
     * <p>仅供 Chat 链路使用——Agent 评审链路不挂载本工具，原因见类级 Javadoc。
     */
    @Bean
    public ToolCallback skillsTool(
            @Value("${cad-review.skills.paths}") List<Resource> skillPaths) {
        return SkillsTool.builder()
                .addSkillsResources(skillPaths)
                .build();
    }

    /**
     * 给业务 Chat 使用的 OpenAI ChatClient，挂载 skillsTool 启用工具调用能力。
     * 与下方 openAiReviewClient 的差异：本 Bean <b>有</b> tool callback，可触发技能；
     * Review 用的同名 Bean 是"裸"客户端，只产出受约束的 JSON 输出。
     */
    @Bean("openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel model, ToolCallback skillsTool) {
        return ChatClient.builder(model)
                .defaultToolCallbacks(skillsTool)
                .build();
    }

    /**
     * 给业务 Chat 使用的 Anthropic ChatClient，挂载 skillsTool 启用工具调用能力。
     * 与 anthropicReviewClient 的关系同上：Chat 端可调工具，Review 端不可。
     */
    @Bean("anthropicChatClient")
    public ChatClient anthropicChatClient(AnthropicChatModel model, ToolCallback skillsTool) {
        return ChatClient.builder(model)
                .defaultToolCallbacks(skillsTool)
                .build();
    }

    /**
     * 给评审 Agent（Dispatcher / Reviewer / Summarizer）使用的 OpenAI ChatClient。
     *
     * <p>故意<b>不</b>挂 skillsTool：评审链路通过 {@code BeanOutputConverter} 强制 JSON 结构化输出，
     * 工具调用会让模型偏离 JSON 模板、破坏 StructuredOutputSupport 的解析逻辑。
     * 模型温度等参数继承 application.yml 的 {@code spring.ai.openai.chat.options}（默认 gpt-4o）。
     */
    @Bean("openAiReviewClient")
    public ChatClient openAiReviewClient(OpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 给评审 Agent 使用的 Anthropic ChatClient。
     *
     * <p>同样不挂 skillsTool（理由见 openAiReviewClient）。
     * 模型/温度/max-tokens 来自 {@code spring.ai.anthropic.chat.options}
     * （默认 claude-sonnet-4-6, temperature=0.2, max-tokens=2048——
     * 低温度有利于结构化输出的稳定性）。
     */
    @Bean("anthropicReviewClient")
    public ChatClient anthropicReviewClient(AnthropicChatModel model) {
        return ChatClient.builder(model).build();
    }

    @Bean("lightweightReviewClient")
    public ChatClient lightweightReviewClient(
            LlmProperties llmProperties,
            @Qualifier("openAiReviewClient") ChatClient openAiReviewClient,
            @Qualifier("anthropicReviewClient") ChatClient anthropicReviewClient) {
        return selectClient(llmProperties.getLightweight().getProvider(), openAiReviewClient, anthropicReviewClient);
    }

    @Bean("deepReviewClient")
    public ChatClient deepReviewClient(
            LlmProperties llmProperties,
            @Qualifier("openAiReviewClient") ChatClient openAiReviewClient,
            @Qualifier("anthropicReviewClient") ChatClient anthropicReviewClient) {
        return selectClient(llmProperties.getDeep().getProvider(), openAiReviewClient, anthropicReviewClient);
    }

    private ChatClient selectClient(Provider provider, ChatClient openAiReviewClient, ChatClient anthropicReviewClient) {
        return provider == Provider.ANTHROPIC ? anthropicReviewClient : openAiReviewClient;
    }
}

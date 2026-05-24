package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Provider;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DispatcherAgent} 单元测试。
 *
 * <p>Dispatcher 负责把 IR 摘要 + 规则集合喂给 LLM，让模型拆出一组 ReviewTask。
 * 这一步是整个评审流水线的入口，任何字段缺失、JSON 不规范、优先级/规则 ID 不合法
 * 都会被这里的规范化逻辑处理掉，因此本类的重点是验证：
 * <ul>
 *   <li>LLM 返回 JSON 后能否被正确解析成 {@link ReviewTask} 列表</li>
 *   <li>关键字段（taskId 编号、priority 枚举、ruleIds）是否经过统一化</li>
 * </ul>
 *
 * <p>整个 ChatModel 被 Mockito 桩成可预测返回，避免依赖真实 LLM。
 */
class DispatcherAgentTest {

    private DispatcherAgent service;
    private ChatModel mockChatModel;

    /** 装配一个最小可运行的 DispatcherAgent：用 mock ChatModel + 单条规则 + 真 ObjectMapper。 */
    @BeforeEach
    void setUp() {
        mockChatModel = mock(ChatModel.class);
        ChatClient reviewClient = ChatClient.builder(mockChatModel).build();
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setDefaultProvider(Provider.OPENAI);

        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setRules(List.of(
                ReviewRule.builder()
                        .id("RULE-1")
                        .clauseId("C-1")
                        .title("规则一")
                        .scope("scope")
                        .promptFragment("fragment")
                        .version("v1")
                        .build()
        ));

        service = new DispatcherAgent(
                reviewClient,
                reviewClient,
                llmProperties,
                agentProperties,
                new ObjectMapper(),
                new com.luckycat.cadreview.prompt.PromptTemplates(new org.springframework.core.io.DefaultResourceLoader()),
                new StructuredOutputSupport(new ObjectMapper()));
    }

    /**
     * 验证最常见的 happy-path：LLM 输出合法 JSON 时，Dispatcher 能把它解析成
     * 一条 ReviewTask，自动补上 TASK-001 形式的 taskId，并把 priority 字符串
     * 转成 {@link RiskLevel} 枚举。
     */
    @Test
    void shouldParseAndNormalizeDispatcherTasks() {
        // 模拟 LLM 返回一条规范的 task JSON
        mockModelResponse("""
                {
                  "tasks": [
                    {
                      "checkItem": "防火分区面积",
                      "ruleIds": ["RULE-1"],
                      "entityIds": ["entity-001"],
                      "layerNames": ["FIRE"],
                      "priority": "HIGH"
                    }
                  ]
                }
                """);

        List<ReviewTask> tasks = service.dispatch(mockIrSummary(), List.of(ReviewRule.builder()
                .id("RULE-1")
                .clauseId("C-1")
                .title("规则一")
                .scope("scope")
                .promptFragment("fragment")
                .version("v1")
                .build()));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTaskId()).isEqualTo("TASK-001");
        assertThat(tasks.get(0).getPriority()).isEqualTo(RiskLevel.HIGH);
        assertThat(tasks.get(0).getRuleIds()).containsExactly("RULE-1");
    }

    @Test
    void shouldCreateEvidenceGapTaskWhenFirstRoundSummarizesWithoutTasks() {
        mockModelResponse("""
                {
                  "nextAgent": "SUMMARIZER",
                  "reason": "缺少直接证据",
                  "tasks": []
                }
                """);

        List<ReviewTask> tasks = service.dispatch(mockIrSummary(), List.of(ReviewRule.builder()
                .id("RULE-1")
                .clauseId("C-1")
                .title("规则一")
                .scope("scope")
                .promptFragment("fragment")
                .version("v1")
                .build()));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTaskId()).isEqualTo("EVIDENCE-GAP-001");
        assertThat(tasks.get(0).getContextPolicy()).isEqualTo("EVIDENCE_GAP_PENDING_REVIEW");
        assertThat(tasks.get(0).getRuleIds()).containsExactly("RULE-1");
    }

    /**
     * 用给定的字符串作为 LLM 的应答内容，组装出一个带 usage 元数据的 ChatResponse
     * 并 stub 进 mockChatModel —— 模拟 LLM 真实回包的结构。
     */
    private void mockModelResponse(String content) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        when(usage.getNativeUsage()).thenReturn(null);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("gpt-4o")
                .usage(usage)
                .build();

        Generation generation = new Generation(new AssistantMessage(content));
        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);

        // 任意 Prompt 调用都返回这条预设响应
        when(mockChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
    }

    /** 构造一个最小化的 IR 摘要节点，只填 schema_version 让 Dispatcher 能跑通模板渲染。 */
    private com.fasterxml.jackson.databind.JsonNode mockIrSummary() {
        return new ObjectMapper().createObjectNode()
                .put("schema_version", "cad-drawing-parser.v1");
    }
}

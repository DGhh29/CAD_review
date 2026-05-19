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

class DispatcherAgentTest {

    private DispatcherAgent service;
    private ChatModel mockChatModel;

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

    @Test
    void shouldParseAndNormalizeDispatcherTasks() {
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

        when(mockChatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
    }

    private com.fasterxml.jackson.databind.JsonNode mockIrSummary() {
        return new ObjectMapper().createObjectNode()
                .put("schema_version", "cad-drawing-parser.v1");
    }
}

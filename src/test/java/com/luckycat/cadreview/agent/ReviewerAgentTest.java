package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Provider;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.dto.enums.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewerAgentTest {

    private ReviewerAgent service;
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

        service = new ReviewerAgent(
                reviewClient,
                reviewClient,
                llmProperties,
                agentProperties,
                new ObjectMapper(),
                new com.luckycat.cadreview.prompt.PromptTemplates(new org.springframework.core.io.DefaultResourceLoader()),
                new StructuredOutputSupport(new ObjectMapper()));
    }

    @Test
    void shouldParseAndNormalizeReviewerFindings() {
        mockModelResponse("""
                {
                  "findings": [
                    {
                      "verdict": "FAIL",
                      "riskLevel": "HIGH",
                      "reason": "面积超限",
                      "clauseId": "C-1",
                      "evidenceEntityIds": ["entity-001"],
                      "confidence": 0.92,
                      "evidenceText": "面积大于上限"
                    }
                  ]
                }
                """);

        List<Finding> findings = service.review(task(), new ObjectMapper().createObjectNode(), List.of(rule()));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(findings.get(0).getRuleId()).isEqualTo("RULE-1");
        assertThat(findings.get(0).getRuleVersion()).isEqualTo("v1");
        assertThat(findings.get(0).getSource()).isEqualTo("REVIEWER_AGENT");
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

    private ReviewTask task() {
        return ReviewTask.builder()
                .taskId("TASK-001")
                .checkItem("防火分区面积")
                .priority(RiskLevel.HIGH)
                .ruleIds(List.of("RULE-1"))
                .areaId("AREA-1")
                .layerNames(List.of("FIRE"))
                .build();
    }

    private ReviewRule rule() {
        return ReviewRule.builder()
                .id("RULE-1")
                .clauseId("C-1")
                .title("规则一")
                .scope("scope")
                .promptFragment("fragment")
                .version("v1")
                .build();
    }
}

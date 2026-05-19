package com.luckycat.cadreview.service;

import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.AuditPackage;
import com.luckycat.cadreview.dto.ReviewMockResponse;
import com.luckycat.cadreview.dto.enums.Provider;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.dto.enums.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewMockServiceTest {

    private ReviewMockService service;
    private ChatModel mockChatModel;

    @BeforeEach
    void setUp() {
        mockChatModel = mock(ChatModel.class);

        // Build real ChatClient from mocked ChatModel
        ChatClient reviewClient = ChatClient.builder(mockChatModel).build();

        LlmProperties props = new LlmProperties();
        props.setDefaultProvider(Provider.OPENAI);
        LlmProperties.Retry retry = new LlmProperties.Retry();
        retry.setMaxAttempts(1);
        props.setRetry(retry);

        service = new ReviewMockService(reviewClient, reviewClient, props, new ObjectMapper());
    }

    @Test
    void shouldParseStructuredFinding() {
        String modelOutput = "{\"findings\":[{\"verdict\":\"PENDING_REVIEW\",\"riskLevel\":\"HIGH\","
                + "\"reason\":\"面积超限\",\"clauseId\":\"demo-fire-zone-area\","
                + "\"evidenceEntityIds\":[\"entity-001\"],\"confidence\":0.86}]}";

        mockModelResponse(modelOutput);

        ReviewMockResponse response = service.review(buildSamplePackage());

        assertThat(response.getFindings()).hasSize(1);
        assertThat(response.getFindings().get(0).getVerdict()).isEqualTo(Verdict.PENDING_REVIEW);
        assertThat(response.getFindings().get(0).getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(response.getFindings().get(0).getConfidence()).isEqualTo(0.86);
        assertThat(response.getFindings().get(0).getEvidenceEntityIds()).containsExactly("entity-001");
    }

    @Test
    void shouldThrowStructuredOutputExceptionOnInvalidJson() {
        mockModelResponse("this is not json at all");

        assertThatThrownBy(() -> service.review(buildSamplePackage()))
                .isInstanceOf(ReviewMockService.StructuredOutputException.class);
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

        when(mockChatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    private AuditPackage buildSamplePackage() {
        AuditPackage pkg = new AuditPackage();
        pkg.setProvider(Provider.OPENAI);
        pkg.setCheckItem("防火分区面积");

        AuditPackage.ProjectInfo pi = new AuditPackage.ProjectInfo();
        pi.setDrawingType("建筑平面图");
        pi.setDiscipline("消防");
        pkg.setProjectInfo(pi);

        pkg.setExtractedParameters(Map.of("area", 1520.5, "unit", "m2", "limit", 1500));

        AuditPackage.Clause clause = new AuditPackage.Clause();
        clause.setClauseId("demo-fire-zone-area");
        clause.setSummary("防火分区面积不应超过 1500 平方米");
        pkg.setClause(clause);

        AuditPackage.Evidence evidence = new AuditPackage.Evidence();
        evidence.setEntityIds(List.of("entity-001"));
        evidence.setLayer("防火分区");
        evidence.setWorldBounds(List.of(0.0, 0.0, 10000.0, 12000.0));
        pkg.setEvidence(evidence);

        return pkg;
    }
}

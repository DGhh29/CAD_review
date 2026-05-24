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

/**
 * {@link ReviewMockService} 单元测试。
 *
 * <p>ReviewMockService 是给前端联调 / 演示用的"单条审核"接口：
 * 输入一个完整的 AuditPackage（项目信息 + 提取参数 + 条款 + 证据），
 * 调一次 LLM 返回结构化的 {@link ReviewMockResponse}。
 *
 * <p>覆盖两条关键路径：
 * <ul>
 *   <li>LLM 给出合法 JSON 时能够正确解析所有结构化字段（verdict / riskLevel / confidence / 证据 ID）</li>
 *   <li>LLM 给出非 JSON 文本时必须抛 {@link ReviewMockService.StructuredOutputException}，
 *       让上游知道这是结构化输出失败而非业务结论</li>
 * </ul>
 *
 * <p>重试次数被强制压成 1，避免单测意外触发多次 mock 调用。
 */
class ReviewMockServiceTest {

    private ReviewMockService service;
    private ChatModel mockChatModel;

    /**
     * 构造一个最小可用的 ReviewMockService：
     * 用 mock ChatModel 包装出 ChatClient，重试次数固定为 1。
     */
    @BeforeEach
    void setUp() {
        mockChatModel = mock(ChatModel.class);

        // 用 mock ChatModel 真正构建一个 ChatClient（不能 mock ChatClient 本身，因为它是 builder 风格）
        ChatClient reviewClient = ChatClient.builder(mockChatModel).build();

        LlmProperties props = new LlmProperties();
        props.setDefaultProvider(Provider.OPENAI);
        LlmProperties.Retry retry = new LlmProperties.Retry();
        // 关闭重试，确保失败用例能稳定抛出，不被自动重试掩盖
        retry.setMaxAttempts(1);
        props.setRetry(retry);

        service = new ReviewMockService(reviewClient, reviewClient, props, new ObjectMapper());
    }

    /**
     * happy path：LLM 输出严格符合 schema 的 JSON 时，
     * 应当被解析成单条 finding，且各结构化字段（verdict、riskLevel、confidence、evidenceEntityIds）
     * 全部回填正确。
     */
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

    /**
     * 失败路径：LLM 返回的不是 JSON（自然语言），
     * 服务必须显式抛 StructuredOutputException —— 不能静默吞掉或编一份空响应。
     */
    @Test
    void shouldThrowStructuredOutputExceptionOnInvalidJson() {
        // 模拟 LLM 没有按 schema 输出，而是给了一段普通文本
        mockModelResponse("this is not json at all");

        assertThatThrownBy(() -> service.review(buildSamplePackage()))
                .isInstanceOf(ReviewMockService.StructuredOutputException.class);
    }

    /**
     * 把任意字符串包装成一个完整的 ChatResponse（含 token usage），
     * 并 stub 进 mockChatModel —— 让 ChatClient 链路能正常跑通。
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

        when(mockChatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    /**
     * 构造一个示例 AuditPackage：建筑平面图 / 消防 / 防火分区面积超限的典型场景，
     * 字段尽量贴近真实业务，避免触发任何"必填字段缺失"分支。
     */
    private AuditPackage buildSamplePackage() {
        AuditPackage pkg = new AuditPackage();
        pkg.setProvider(Provider.OPENAI);
        pkg.setCheckItem("防火分区面积");

        AuditPackage.ProjectInfo pi = new AuditPackage.ProjectInfo();
        pi.setDrawingType("建筑平面图");
        pi.setDiscipline("消防");
        pkg.setProjectInfo(pi);

        // 实测面积 1520.5 已经超过限值 1500，符合预期的 PENDING_REVIEW/HIGH 场景
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

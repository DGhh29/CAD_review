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

/**
 * {@link ReviewerAgent} 单元测试。
 *
 * <p>Reviewer 负责单条任务的实际审核：拿到任务、相关 IR 切片与对应规则后，
 * 调 LLM 让它给出 Finding 列表（结论 + 风险等级 + 证据 + 引用条款）。
 *
 * <p>本测试聚焦在<b>结构化输出解析</b>这一关键边界：当 LLM 给出符合 schema 的 JSON 时，
 * Reviewer 必须把字段映射到 {@link Finding} 上，并自动补齐
 * 任务上下文里才有的元数据（ruleId、ruleVersion、source=REVIEWER_AGENT）。
 *
 * <p>更复杂的失败/重试/降级路径由 ReviewMockServiceTest 与 AgentOrchestratorTest 覆盖。
 */
class ReviewerAgentTest {

    private ReviewerAgent service;
    private ChatModel mockChatModel;

    /** 装配最小可用 ReviewerAgent：mock ChatModel + 单条规则。 */
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

    /**
     * happy path：LLM 返回一条 FAIL/HIGH 的 finding，Reviewer 应当：
     * 1) 把 verdict / riskLevel 字符串解成枚举；
     * 2) 把任务侧的 ruleId / ruleVersion 注入回 Finding；
     * 3) 把 source 标记为 REVIEWER_AGENT —— 用于在汇总阶段区分来源。
     */
    @Test
    void shouldParseAndNormalizeReviewerFindings() {
        // 模拟 LLM 给出一条规范的 finding
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

    /** 模拟 LLM 真实回包：带 token usage 元数据 + 一条 assistant 消息。 */
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

    /** 单条任务样本：HIGH 优先级，绑定 RULE-1，挂在 FIRE 图层。 */
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

    /** 与 task() 相对应的规则：版本 v1，会被回写到 Finding.ruleVersion 上。 */
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

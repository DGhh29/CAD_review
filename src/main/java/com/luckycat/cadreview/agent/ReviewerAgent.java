package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Verdict;
import com.luckycat.cadreview.prompt.PromptTemplates;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单任务审核 Agent：拿到 Dispatcher 拆好的 {@link ReviewTask}、对应的"切片 IR"和适用规则，
 * 调用 LLM 输出一组 {@link Finding}。
 *
 * <p>它在多 Agent 流水线里位于第三步，由 {@link com.luckycat.cadreview.agent.AgentOrchestrator}
 * 通过 reviewerTaskExecutor 线程池并发触发，多 task 并行跑互不干扰。
 *
 * <p>关键设计：
 * <ul>
 *   <li>只接收 {@link IrViewService#slice} 切片后的"相关 IR"，把上下文压到与本 task 直接相关的图元/图层 ——
 *       否则一张完整的复杂图喂下去 token 会爆炸，也容易让 LLM 注意力分散。</li>
 *   <li>每条 Finding 必须能"锚定"到具体证据：FAIL 结论强制要求 ruleId + evidenceText + 实体或 bbox 之一，
 *       PENDING_REVIEW 必须给出 reason。这是为了保证审核结论可追溯、可视化。</li>
 *   <li>ruleId 必须落在当前 task 的 ruleIds 白名单内，防 LLM 跨任务幻觉。</li>
 * </ul>
 */
@Slf4j
@Service
public class ReviewerAgent {

    private final AgentModelRouter agentModelRouter;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;
    private final ContextBudgetService contextBudgetService;

    @Autowired
    public ReviewerAgent(
            AgentModelRouter agentModelRouter,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport,
            ContextBudgetService contextBudgetService) {
        this.agentModelRouter = agentModelRouter;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
        this.contextBudgetService = contextBudgetService;
    }

    ReviewerAgent(
            ChatClient openAiReviewClient,
            ChatClient anthropicReviewClient,
            LlmProperties llmProperties,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport) {
        this.agentModelRouter = new AgentModelRouter(openAiReviewClient, anthropicReviewClient);
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
        this.contextBudgetService = new ContextBudgetService(objectMapper, agentProperties);
    }

    /**
     * 让 LLM 在切片 IR 上按规则集判断，输出该任务的 Finding 列表。
     *
     * <p>用户提示打包了三块信息：
     * <ul>
     *   <li>{@code task} —— 让 LLM 知道"我现在审的具体是什么"（区域/规则范围）。</li>
     *   <li>{@code rules} —— promptFragment 提供具体规则文本，避免它凭印象凑结论。</li>
     *   <li>{@code relevantIr} —— 切片后的图纸数据，是它的实际"证据池"。</li>
     * </ul>
     *
     * @param task        当前要审核的任务（非空）。
     * @param relevantIr  仅包含与本 task 相关实体/图层的 IR 切片。
     * @param rules       与 task.ruleIds 一一对应、已解析好的规则对象列表（非空）。
     * @return 经过校验/补默认值的 Finding 列表，可直接交给 SummarizerAgent。
     */
    public List<Finding> review(ReviewTask task, JsonNode relevantIr, List<ReviewRule> rules) {
        if (task == null) {
            throw new IllegalArgumentException("ReviewTask must not be null");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Reviewer must receive at least one rule");
        }
        JsonNode promptContext = objectMapper.valueToTree(Map.of(
                "task", task,
                "rules", rules,
                "relevantIr", relevantIr
        ));
        String userPrompt = contextBudgetService.toPromptJson(
                contextBudgetService.wrap(AgentRole.REVIEWER, "REVIEWER", task.getTaskId(), promptContext));
        StructuredOutputSupport.StructuredResult<ReviewerOutput> result = structuredOutputSupport.call(
                agentModelRouter.clientFor(AgentRole.REVIEWER),
                promptTemplates.reviewerSystem(),
                userPrompt,
                ReviewerOutput.class,
                agentProperties.getReviewer().getMaxAttempts(),
                // 操作名加上 taskId，使日志能定位到具体哪个 task 的 LLM 调用挂了
                "reviewer-" + task.getTaskId(),
                output -> validateOutput(output, task, rules)
        );
        return result.getOutput().getFindings();
    }

    /**
     * 校验整体 LLM 输出：缺 findings 字段时补空列表，再逐条 finding 调 {@link #validateFinding}。
     * 任一 finding 校验失败都会冒泡触发整体重试。
     */
    private ReviewerOutput validateOutput(ReviewerOutput output, ReviewTask task, List<ReviewRule> rules) {
        if (output == null) {
            throw new IllegalArgumentException("Reviewer output is null");
        }
        if (output.getFindings() == null) {
            output.setFindings(new ArrayList<>());
        }
        // LinkedHashMap 保留规则顺序：后续 validateFinding 里若需要按 task 首条规则做兜底，顺序稳定。
        Map<String, ReviewRule> ruleMap = new LinkedHashMap<>();
        for (ReviewRule rule : rules) {
            ruleMap.put(rule.getId(), rule);
        }
        for (Finding finding : output.getFindings()) {
            validateFinding(finding, task, ruleMap);
        }
        return output;
    }

    /**
     * 单条 Finding 的强校验 + 默认值补全。
     *
     * <p>核心约束（确保 Finding 可追溯）：
     * <ul>
     *   <li>verdict 必须存在 —— 没有结论的 Finding 没意义。</li>
     *   <li>ruleId 必须落在 task.ruleIds 白名单内 —— 防 LLM 把别的 task 的规则带过来。</li>
     *   <li>FAIL：必须同时提供 ruleId + evidenceText + 实体或 boundingBox 至少一个 ——
     *       这样前端才能高亮到底是图里哪里不合规。</li>
     *   <li>PENDING_REVIEW：必须有 reason 解释为何无法判定。</li>
     *   <li>confidence 落在 [0,1] 区间。</li>
     *   <li>areaId 必须等于 task.areaId —— 一条 Finding 不允许跨区域，否则覆盖率/冲突检测会乱。</li>
     * </ul>
     *
     * <p>同时帮 LLM 补常见缺失字段：layerNames 缺省继承 task 的、source 默认 "REVIEWER_AGENT"、
     * ruleVersion / clauseId 从规则定义里回填，减轻 LLM 写完整字段的负担。
     */
    private void validateFinding(Finding finding, ReviewTask task, Map<String, ReviewRule> ruleMap) {
        if (finding == null) {
            throw new IllegalArgumentException("Finding must not be null");
        }
        if (finding.getVerdict() == null) {
            throw new IllegalArgumentException("Finding verdict is required");
        }
        if (finding.getEvidenceEntityIds() == null) {
            finding.setEvidenceEntityIds(new ArrayList<>());
        }
        if (finding.getLayerNames() == null || finding.getLayerNames().isEmpty()) {
            // 没填图层时继承 task 的图层范围，至少能定位到大致区域
            finding.setLayerNames(new ArrayList<>(task.getLayerNames() == null ? List.of() : task.getLayerNames()));
        }
        if (finding.getMissingEvidence() == null) {
            finding.setMissingEvidence(new ArrayList<>());
        }
        if (finding.getRepairHints() == null) {
            finding.setRepairHints(new ArrayList<>());
        }
        if (isBlank(finding.getSource())) {
            finding.setSource("REVIEWER_AGENT");
        }
        if (isBlank(finding.getAreaId())) {
            finding.setAreaId(task.getAreaId());
        } else if (!finding.getAreaId().equals(task.getAreaId())) {
            // areaId 与任务不一致直接拒绝 —— 后续 SummarizerAgent 的冲突检测以 areaId+clauseId 为主键
            throw new IllegalArgumentException("Finding areaId must match task areaId");
        }
        List<String> taskRuleIds = task.getRuleIds() == null ? List.of() : task.getRuleIds();
        if (isBlank(finding.getRuleId()) && !taskRuleIds.isEmpty()) {
            // ruleId 空时取 task 首条规则兜底
            finding.setRuleId(taskRuleIds.get(0));
        }
        if (!taskRuleIds.contains(finding.getRuleId())) {
            throw new IllegalArgumentException("Finding ruleId must belong to current task: " + finding.getRuleId());
        }
        ReviewRule rule = ruleMap.get(finding.getRuleId());
        if (rule != null) {
            if (isBlank(finding.getRuleVersion())) {
                finding.setRuleVersion(rule.getVersion());
            }
            if (isBlank(finding.getClauseId())) {
                finding.setClauseId(rule.getClauseId());
            }
        }
        if (finding.getConfidence() != null
                && (finding.getConfidence() < 0.0d || finding.getConfidence() > 1.0d)) {
            throw new IllegalArgumentException("Finding confidence must be between 0.0 and 1.0");
        }
        if (finding.getVerdict() == Verdict.FAIL) {
            // FAIL 结论必须能在图上锚定到具体位置，否则前端无法高亮、人工无法复核
            if (isBlank(finding.getRuleId()) || isBlank(finding.getEvidenceText()) || !hasAnchor(finding)) {
                throw new IllegalArgumentException("FAIL finding must contain ruleId, evidenceText and entity/boundingBox anchor");
            }
        }
        if (finding.getVerdict() == Verdict.PENDING_REVIEW && isBlank(finding.getReason())) {
            throw new IllegalArgumentException("PENDING_REVIEW finding must contain reason");
        }
    }

    /**
     * 判断 finding 是否拥有"图上的锚点"：要么挂了具体的 entity，要么至少给出 4 维 bbox。
     * 没有锚点的 FAIL 是不可接受的 —— 前端无法高亮告诉用户问题在哪。
     */
    private boolean hasAnchor(Finding finding) {
        boolean hasEntity = finding.getEvidenceEntityIds() != null && !finding.getEvidenceEntityIds().isEmpty();
        boolean hasBoundingBox = finding.getBoundingBox() != null && finding.getBoundingBox().size() >= 4;
        return hasEntity || hasBoundingBox;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Reviewer 的 LLM 输出契约：本 task 触发的所有 Finding。
     * BeanOutputConverter 据此生成 schema 描述并反序列化。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewerOutput {
        private List<Finding> findings = new ArrayList<>();
    }
}

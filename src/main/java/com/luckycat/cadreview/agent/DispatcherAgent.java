package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.prompt.PromptTemplates;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务分派 Agent：把"图纸 IR 摘要 + 适用规则集"喂给 LLM，
 * 让它读完整张图后拆分出一组结构化 {@link ReviewTask}，每个 task 指明
 * "这一段图（哪些 entity / 哪些图层 / 哪个区域）应该按哪条规则去审"。
 *
 * <p>它在多 Agent 流水线里位于第二步，处于 {@link com.luckycat.cadreview.parser.CadParserService}
 * 之后、{@link ReviewerAgent} 之前。下游的并行 Reviewer 数量、每条规则的覆盖度，
 * 完全取决于本 Agent 拆出的任务数量与质量。
 *
 * <p>关键决策：
 * <ul>
 *   <li>用 {@link IrViewService#buildSummary} 后的 IR 摘要而非原始 IR —— 全量 IR 一般 MB 级，会撑爆 LLM 上下文。</li>
 *   <li>通过 {@link StructuredOutputSupport} 强制 LLM 输出严格 JSON，并配合
 *       {@link #validateOutput} 做规则白名单校验，避免幻觉出根本不存在的 ruleId。</li>
 *   <li>提供静态 {@link #taskComparator()}，给 {@code AgentOrchestrator.limitTasks} 用作截断排序：
 *       优先级 HIGH/MEDIUM/LOW 反向排序，让高优任务永远不被截断丢弃。</li>
 * </ul>
 */
@Slf4j
@Service
public class DispatcherAgent {

    private final AgentModelRouter agentModelRouter;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;
    private final RegulationPlannerAgent regulationPlannerAgent;
    private final ContextBudgetService contextBudgetService;

    @Autowired
    public DispatcherAgent(
            AgentModelRouter agentModelRouter,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport,
            RegulationPlannerAgent regulationPlannerAgent,
            ContextBudgetService contextBudgetService) {
        this.agentModelRouter = agentModelRouter;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
        this.regulationPlannerAgent = regulationPlannerAgent;
        this.contextBudgetService = contextBudgetService;
    }

    DispatcherAgent(
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
        this.regulationPlannerAgent = new RegulationPlannerAgent();
        this.contextBudgetService = new ContextBudgetService(objectMapper, agentProperties);
    }

    /**
     * 让 LLM 基于摘要 IR 与规则集合，分派出一组 {@link ReviewTask}。
     *
     * <p>用户提示里同时塞入 {@code rules} 与 {@code irSummary}：
     * 前者让 LLM 知道"可以挑哪些规则"（避免凭空臆造规则），
     * 后者让它知道"图里有什么内容"（消防/停车/标注 等区域线索）。
     *
     * @param irSummary 由 {@link IrViewService#buildSummary} 压缩过的图纸摘要视图。
     * @param rules     本次评审启用的规则；为空时直接返回空列表，不浪费一次 LLM 调用。
     * @return 已规范化（补齐 taskId/checkItem/areaId 等默认值）并按优先级排序的任务列表。
     */
    public List<ReviewTask> dispatch(JsonNode irSummary, List<ReviewRule> rules) {
        return dispatchRound(irSummary, rules, null).getTasks();
    }

    /**
     * 让 Dispatcher 基于当前轮次上下文决定下一步动作。
     *
     * <p>第一轮通常输出 REVIEWER + 任务列表；后续轮次会同时看到已有 findings /
     * 成功失败任务等运行状态，可以继续补充 ReviewTask，也可以选择 SUMMARIZER 收口。
     */
    public DispatchRoundOutput dispatchRound(JsonNode irSummary, List<ReviewRule> rules, JsonNode runState) {
        if (rules == null || rules.isEmpty()) {
            return DispatchRoundOutput.summarizer("未配置可用审核规则");
        }
        // Dispatcher 只看规则摘要和图纸摘要，完整规则正文留给 Reviewer 使用。
        JsonNode promptContext = objectMapper.valueToTree(Map.of(
                "rules", regulationPlannerAgent.digest(rules),
                "irSummary", irSummary,
                "runState", runState == null ? objectMapper.createObjectNode() : runState
        ));
        String userPrompt = contextBudgetService.toPromptJson(
                contextBudgetService.wrap(AgentRole.DISPATCHER, "DISPATCHER", null, promptContext));
        StructuredOutputSupport.StructuredResult<DispatchRoundOutput> result = structuredOutputSupport.call(
                agentModelRouter.clientFor(AgentRole.DISPATCHER),
                promptTemplates.dispatcherSystem(),
                userPrompt,
                DispatchRoundOutput.class,
                agentProperties.getDispatcher().getMaxAttempts(),
                "dispatcher",
                output -> validateOutput(output, rules, runState)
        );
        return result.getOutput();
    }

    /**
     * 校验并规范化 LLM 输出：
     * <ul>
     *   <li>output / tasks 字段缺失时直接抛错或补空列表（兜底防 NPE）。</li>
     *   <li>逐个 task 调 {@link #normalizeTask} 补默认值 / 校验 ruleId 白名单。</li>
     *   <li>最后按 {@link #taskComparator()} 重排，保证截断时优先保留高优任务。</li>
     * </ul>
     * 任何一步抛异常都会被 StructuredOutputSupport 捕获并触发重试。
     */
    private DispatchRoundOutput validateOutput(DispatchRoundOutput output, List<ReviewRule> rules, JsonNode runState) {
        if (output == null) {
            throw new IllegalArgumentException("Dispatcher output is null");
        }
        if (output.getNextAgent() == null) {
            output.setNextAgent(DispatcherNextAgent.REVIEWER);
        }
        if (output.getTasks() == null) {
            output.setTasks(new ArrayList<>());
        }
        if (isFirstRound(runState) && output.getNextAgent() == DispatcherNextAgent.SUMMARIZER) {
            return evidenceGapOutput(rules, output.getReason());
        }
        if (output.getNextAgent() == DispatcherNextAgent.SUMMARIZER) {
            output.setTasks(new ArrayList<>());
            if (isBlank(output.getReason())) {
                output.setReason("Dispatcher decided to summarize");
            }
            return output;
        }
        Map<String, ReviewRule> ruleMap = agentProperties.ruleMap(rules);
        List<ReviewTask> normalized = new ArrayList<>();
        int index = 1;
        for (ReviewTask task : output.getTasks()) {
            if (task == null) {
                continue;
            }
            normalizeTask(task, index++, ruleMap);
            normalized.add(task);
        }
        normalized.sort(taskComparator());
        output.setTasks(normalized);
        if (normalized.isEmpty()) {
            if (isFirstRound(runState)) {
                return evidenceGapOutput(rules, output.getReason());
            }
            output.setNextAgent(DispatcherNextAgent.SUMMARIZER);
            if (isBlank(output.getReason())) {
                output.setReason("无法识别新的审核任务");
            }
        }
        return output;
    }

    private boolean isFirstRound(JsonNode runState) {
        return runState == null || runState.path("nextRound").asInt(1) <= 1;
    }

    private DispatchRoundOutput evidenceGapOutput(List<ReviewRule> rules, String reason) {
        DispatchRoundOutput output = new DispatchRoundOutput();
        output.setNextAgent(DispatcherNextAgent.REVIEWER);
        output.setReason(isBlank(reason) ? "首轮缺少直接证据，转为证据缺失复核任务" : reason);
        List<ReviewTask> tasks = new ArrayList<>();
        int index = 1;
        Map<String, ReviewRule> ruleMap = agentProperties.ruleMap(rules);
        for (ReviewRule rule : rules) {
            ReviewTask task = ReviewTask.builder()
                    .taskId(String.format("EVIDENCE-GAP-%03d", index++))
                    .checkItem("证据缺失复核：" + rule.getTitle())
                    .ruleIds(List.of(rule.getId()))
                    .areaId("UNKNOWN")
                    .contextPolicy("EVIDENCE_GAP_PENDING_REVIEW")
                    .priority(RiskLevel.MEDIUM)
                    .build();
            task.setCategory(inferCategory(task, ruleMap));
            tasks.add(task);
        }
        tasks.sort(taskComparator());
        output.setTasks(tasks);
        return output;
    }

    /**
     * 单个 ReviewTask 的规范化逻辑，主要做"防呆校验 + 补默认值"：
     * <ul>
     *   <li>ruleIds 必须非空，且每个 id 都得在本次启用规则白名单内 —— 防 LLM 幻觉。</li>
     *   <li>用 {@link LinkedHashSet} 去重 ruleIds，保留 LLM 给出的相对顺序。</li>
     *   <li>taskId / checkItem / areaId 缺失时补成可识别的默认值（TASK-NNN / 规则标题 / "UNKNOWN"），
     *       避免下游 Reviewer 因字段为空而失败。</li>
     *   <li>priority 缺失时默认 MEDIUM，避免影响 {@link #taskComparator()} 的排序结果。</li>
     * </ul>
     */
    private void normalizeTask(ReviewTask task, int index, Map<String, ReviewRule> ruleMap) {
        if (task.getRuleIds() == null || task.getRuleIds().isEmpty()) {
            throw new IllegalArgumentException("ReviewTask must contain at least one ruleId");
        }
        Set<String> distinctRuleIds = new LinkedHashSet<>(task.getRuleIds());
        for (String ruleId : distinctRuleIds) {
            if (!ruleMap.containsKey(ruleId)) {
                throw new IllegalArgumentException("Unknown ruleId in ReviewTask: " + ruleId);
            }
        }
        task.setRuleIds(new ArrayList<>(distinctRuleIds));
        if (isBlank(task.getTaskId())) {
            task.setTaskId(String.format("TASK-%03d", index));
        }
        if (isBlank(task.getCheckItem())) {
            // 用首条规则的标题作为 checkItem 兜底文案，至少让人能看出这个任务在审什么。
            task.setCheckItem(ruleMap.get(task.getRuleIds().get(0)).getTitle());
        }
        if (isBlank(task.getCategory())) {
            task.setCategory(inferCategory(task, ruleMap));
        }
        if (task.getEntityIds() == null) {
            task.setEntityIds(new ArrayList<>());
        }
        if (task.getLayerNames() == null) {
            task.setLayerNames(new ArrayList<>());
        }
        if (task.getEvidenceGroups() == null) {
            task.setEvidenceGroups(new ArrayList<>());
        }
        if (isBlank(task.getContextPolicy())) {
            task.setContextPolicy("AUTO_BUDGET");
        }
        if (isBlank(task.getAreaId())) {
            task.setAreaId("UNKNOWN");
        }
        if (task.getPriority() == null) {
            task.setPriority(RiskLevel.MEDIUM);
        }
    }

    /**
     * ReviewTask 的全局排序器。
     *
     * <p>给 {@code AgentOrchestrator.limitTasks} 在任务数量超过 maxReviewTasks 时做截断用 ——
     * 排序后的前 N 个任务会被保留下来执行 Reviewer，其余降级为 skipped。
     *
     * <p>排序键依次为：
     * <ol>
     *   <li>priority 反序：HIGH(3) → MEDIUM(2) → LOW(1) → null(0)，优先级越高越靠前。</li>
     *   <li>首条 ruleId 升序：让同一条规则的任务在前后顺序上稳定。</li>
     *   <li>layerNames 拼接串升序：同规则下按图层名继续稳定排序。</li>
     *   <li>taskId 升序（null 排末尾）：兜底防排序非确定。</li>
     * </ol>
     */
    static Comparator<ReviewTask> taskComparator() {
        return Comparator
                .comparingInt((ReviewTask task) -> priorityRank(task.getPriority())).reversed()
                .thenComparing(task -> first(task.getRuleIds()))
                .thenComparing(task -> String.join(",", task.getLayerNames() == null ? List.of() : task.getLayerNames()))
                .thenComparing(ReviewTask::getTaskId, Comparator.nullsLast(String::compareTo));
    }

    /** 把 RiskLevel 映射到一个用于排序的整数，便于 reversed() 后高优先级排前面。 */
    private static int priorityRank(RiskLevel priority) {
        if (priority == RiskLevel.HIGH) {
            return 3;
        }
        if (priority == RiskLevel.MEDIUM) {
            return 2;
        }
        if (priority == RiskLevel.LOW) {
            return 1;
        }
        return 0;
    }

    /** 取列表首元素的小工具，空列表返回空串以避免空指针并保持排序稳定。 */
    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String inferCategory(ReviewTask task, Map<String, ReviewRule> ruleMap) {
        StringBuilder builder = new StringBuilder(task.getCheckItem() == null ? "" : task.getCheckItem());
        for (String ruleId : task.getRuleIds() == null ? List.<String>of() : task.getRuleIds()) {
            ReviewRule rule = ruleMap.get(ruleId);
            if (rule != null) {
                builder.append(' ').append(rule.getTitle()).append(' ').append(rule.getScope());
            }
        }
        String text = builder.toString().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("消防") || text.contains("防火") || text.contains("疏散")) {
            return "fire";
        }
        if (text.contains("停车") || text.contains("充电")) {
            return "parking";
        }
        if (text.contains("红线") || text.contains("退距") || text.contains("用地")) {
            return "site_plan";
        }
        if (text.contains("指标") || text.contains("容积率") || text.contains("绿地率")) {
            return "planning_indicator";
        }
        return "general";
    }

    /**
     * Dispatcher 的 LLM 输出契约：仅包含一组 ReviewTask。
     * 由 BeanOutputConverter 据此推断 JSON Schema 并反序列化 LLM 回包。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatchRoundOutput {
        /** 下一步应该交给哪个 Agent。 */
        private DispatcherNextAgent nextAgent = DispatcherNextAgent.REVIEWER;

        /** 本轮 Dispatcher 的调度理由，便于报告和日志解释。 */
        private String reason;

        /** 本轮需要下发给 Reviewer 的任务。 */
        private List<ReviewTask> tasks = new ArrayList<>();

        static DispatchRoundOutput summarizer(String reason) {
            DispatchRoundOutput output = new DispatchRoundOutput();
            output.setNextAgent(DispatcherNextAgent.SUMMARIZER);
            output.setReason(reason);
            output.setTasks(new ArrayList<>());
            return output;
        }
    }
}

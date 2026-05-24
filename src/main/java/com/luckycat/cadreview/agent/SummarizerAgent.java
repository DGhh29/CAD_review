package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.ConflictGroup;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewCoverage;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.Verification;
import com.luckycat.cadreview.dto.enums.Provider;
import com.luckycat.cadreview.dto.enums.RiskLevel;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 评审汇总 Agent：把所有 Reviewer 输出的 {@link Finding} 聚合、去冲突、计算覆盖率，
 * 最终生成对外的 {@link ReviewReport}。
 *
 * <p>它在多 Agent 流水线里是最后一步，由 {@link com.luckycat.cadreview.agent.AgentOrchestrator}
 * 在 Reviewer 收割完毕后调用。即便上游某个阶段失败/超时，Orchestrator 也一定会走到这里，
 * 让前端拿到一份"哪怕是降级也是有结构"的报告。
 *
 * <p>核心责任：
 * <ul>
 *   <li>{@link #detectConflicts} —— 在同 clauseId 上检测 PASS/FAIL 互斥结论，挑出来作为 ConflictGroup。</li>
 *   <li>{@link #applyVerification} —— 对高风险且置信度偏低 / 卷入冲突的 Finding 触发二次 LLM 复核。</li>
 *   <li>{@link #decideVerdict} —— 综合 finding 结论 / 冲突 / 失败任务 / 未锚定比例，决定整体 Verdict。</li>
 *   <li>{@link #buildReason} —— 把所有降级原因拼成一段人类可读的 reason 文本。</li>
 * </ul>
 *
 * <p>注意：这里的 LLM 调用只用于"高风险 Finding 的二次校验"，不再扩展原始结论；
 * 把成本控制在能影响最终判定的关键 Finding 上。
 */
@Slf4j
@Service
public class SummarizerAgent {

    private final ChatClient openAiReviewClient;
    private final ChatClient anthropicReviewClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;
    private final AgentModelRouter agentModelRouter;
    private final ContextBudgetService contextBudgetService;

    @Autowired
    public SummarizerAgent(
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport,
            AgentModelRouter agentModelRouter,
            ContextBudgetService contextBudgetService) {
        this.openAiReviewClient = null;
        this.anthropicReviewClient = null;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
        this.agentModelRouter = agentModelRouter;
        this.contextBudgetService = contextBudgetService == null
                ? new ContextBudgetService(this.objectMapper, agentProperties)
                : contextBudgetService;
    }

    SummarizerAgent(
            ChatClient openAiReviewClient,
            ChatClient anthropicReviewClient,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport) {
        this.openAiReviewClient = openAiReviewClient;
        this.anthropicReviewClient = anthropicReviewClient;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
        this.agentModelRouter = openAiReviewClient == null && anthropicReviewClient == null
                ? null
                : new AgentModelRouter(openAiReviewClient, anthropicReviewClient);
        this.contextBudgetService = new ContextBudgetService(this.objectMapper, agentProperties);
    }

    /**
     * 把 Reviewer 阶段的全部产物聚合成 ReviewReport 的入口方法。
     *
     * <p>处理顺序：冲突检测 → 高风险二次校验 → 覆盖率统计 → 整体 Verdict 决定 → reason 组合，
     * 最终装配 ReviewReport 返回。任意阶段出错都不会抛 —— 这里是兜底环节，必须给出报告。
     */
    public ReviewReport summarize(ReviewSummaryInput input) {
        // 拷贝 findings 避免直接修改入参列表
        List<Finding> findings = input.getFindings() == null ? new ArrayList<>() : new ArrayList<>(input.getFindings());
        List<ReviewTask> succeededTasks = nullToList(input.getSucceededTasks());
        List<ReviewTask> failedTasks = nullToList(input.getFailedTasks());
        List<ReviewTask> skippedTasks = nullToList(input.getSkippedTasks());

        List<ConflictGroup> conflicts = detectConflicts(findings);
        // 二次校验会写回 finding.verification，所以必须发生在装配 ReviewReport 之前
        applyVerification(findings, conflicts);

        List<String> failedTaskIds = taskIds(failedTasks);
        List<String> skippedTaskIds = taskIds(skippedTasks);
        List<String> skippedRuleIds = collectSkippedRuleIds(skippedTasks, input.getSkippedRuleIds());
        int totalTasks = succeededTasks.size() + failedTasks.size() + skippedTasks.size();
        int unanchoredCount = countUnanchored(findings);
        double taskCoverageRate = totalTasks == 0 ? 0.0d : (double) succeededTasks.size() / totalTasks;
        double unanchoredRate = findings.isEmpty() ? 0.0d : (double) unanchoredCount / findings.size();

        ReviewCoverage coverage = ReviewCoverage.builder()
                .totalTasks(totalTasks)
                .succeededTasks(succeededTasks.size())
                .failedTasks(failedTasks.size())
                .skippedTasks(skippedTasks.size())
                .unanchoredCount(unanchoredCount)
                .taskCoverageRate(taskCoverageRate)
                .unanchoredRate(unanchoredRate)
                .failedTaskIds(failedTaskIds)
                .skippedTaskIds(skippedTaskIds)
                .skippedRuleIds(skippedRuleIds)
                .build();

        Verdict overallVerdict = decideVerdict(findings, conflicts, failedTasks, skippedTasks, totalTasks, unanchoredRate);
        boolean partial = input.isPartial() || !failedTasks.isEmpty() || !skippedTasks.isEmpty();
        String reason = buildReason(input.getReason(), findings, conflicts, failedTasks, skippedTasks, totalTasks, unanchoredRate);

        return ReviewReport.builder()
                .reportId(UUID.randomUUID().toString())
                .overallVerdict(overallVerdict)
                .findings(findings)
                .conflicts(conflicts)
                .coverage(coverage)
                .failedTaskIds(failedTaskIds)
                .partial(partial)
                .durationMs(input.getDurationMs())
                .totalTokens(input.getTotalTokens())
                .reason(reason)
                .build();
    }

    /**
     * O(N²) 双层遍历两两比对，把"同 clauseId 但 verdict 互斥（PASS vs FAIL）且发生在同一区域/同一实体/同一bbox范围"
     * 的 Finding 配对成 ConflictGroup。
     *
     * <p>不同 clauseId 之间的不同结论是允许的（不同条款本来就可能各自命中），所以 clauseId 必须相同才算冲突。
     * Finding 数量在实际场景里不会很大，没引入更复杂的索引结构。
     */
    private List<ConflictGroup> detectConflicts(List<Finding> findings) {
        List<ConflictGroup> conflicts = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            for (int j = i + 1; j < findings.size(); j++) {
                Finding left = findings.get(i);
                Finding right = findings.get(j);
                if (isConflict(left, right)) {
                    conflicts.add(ConflictGroup.builder()
                            .areaId(resolveConflictArea(left, right))
                            .clauseId(left.getClauseId())
                            .conflictingFindings(List.of(left, right))
                            .build());
                }
            }
        }
        return conflicts;
    }

    /**
     * 是否构成冲突的判定逻辑：
     * <ol>
     *   <li>双方均已下结论。</li>
     *   <li>clauseId 相同且非空。</li>
     *   <li>结论一 PASS 一 FAIL（互斥）。</li>
     *   <li>三者之一成立：实体集合有交集 / bounding box 矩形重叠 / areaId 相同。</li>
     * </ol>
     */
    private boolean isConflict(Finding left, Finding right) {
        if (left.getVerdict() == null || right.getVerdict() == null) {
            return false;
        }
        if (!sameNonBlank(left.getClauseId(), right.getClauseId())) {
            return false;
        }
        boolean opposite = (left.getVerdict() == Verdict.PASS && right.getVerdict() == Verdict.FAIL)
                || (left.getVerdict() == Verdict.FAIL && right.getVerdict() == Verdict.PASS);
        if (!opposite) {
            return false;
        }
        return entityOverlap(left, right)
                || boundingBoxOverlap(left.getBoundingBox(), right.getBoundingBox())
                || sameNonBlank(left.getAreaId(), right.getAreaId());
    }

    /**
     * 触发二次 LLM 校验：对"需要校验"的 Finding 调 {@link #verify} 写回 verification 字段。
     *
     * <p>"需要校验"的 finding = 高风险且置信度低于阈值（{@link #needsVerification}）+ 卷入冲突的全部 finding。
     * 用 {@code IdentityHashMap}-backed Set 是为了基于对象引用去重 —— 同一个 Finding 既高风险又卷入冲突时只校验一次。
     *
     * <p>校验异常不会影响主流程，会把 verification 标为 FAILED 并记录原因。
     */
    private void applyVerification(List<Finding> findings, List<ConflictGroup> conflicts) {
        if (!agentProperties.getSummarizer().isVerificationEnabled()) {
            return;
        }
        // 必须按引用去重：业务上 Finding 没实现 equals，普通 HashSet 会用 Lombok 生成的 equals 把"内容相同"的对象误判为同一对象
        Set<Finding> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Finding finding : findings) {
            if (needsVerification(finding)) {
                targets.add(finding);
            }
        }
        for (ConflictGroup conflict : conflicts) {
            targets.addAll(conflict.getConflictingFindings());
        }
        for (Finding finding : targets) {
            try {
                finding.setVerification(verify(finding));
            } catch (Exception ex) {
                // 二次校验失败不影响主报告，降级为带 FAILED 状态的 verification 即可
                finding.setVerification(Verification.builder()
                        .status("FAILED")
                        .verifiedBy(agentProperties.getSummarizer().getVerifyProvider().name())
                        .comment(ex.getMessage())
                        .build());
                log.warn("Finding verification failed: {}", ex.getMessage());
            }
        }
    }

    /** 高风险（HIGH）但置信度低于阈值的 Finding 才走二次校验，以控制成本。 */
    private boolean needsVerification(Finding finding) {
        return finding.getRiskLevel() == RiskLevel.HIGH
                && finding.getConfidence() != null
                && finding.getConfidence() < agentProperties.getSummarizer().getVerifyConfidenceThreshold();
    }

    /**
     * 向配置指定的 Provider 发起一次"复核"调用：
     * 把 Finding 单独 JSON 化送给 LLM，让它独立给出 verifiedVerdict + verifiedReason。
     * 用同一份 summarizer 系统提示，但配合 {@link VerificationOutput} 限定结构化字段。
     */
    private Verification verify(Finding finding) {
        Provider provider = agentProperties.getSummarizer().getVerifyProvider();
        String userPrompt = contextBudgetService.toPromptJson(
                contextBudgetService.wrap(
                        AgentRole.VERIFIER,
                        "SUMMARIZER_VERIFICATION",
                        null,
                        objectMapper.valueToTree(Map.of("finding", finding))));
        ChatClient client = agentModelRouter != null
                ? agentModelRouter.clientFor(AgentRole.VERIFIER)
                : (provider == Provider.ANTHROPIC ? anthropicReviewClient : openAiReviewClient);
        StructuredOutputSupport.StructuredResult<VerificationOutput> result = structuredOutputSupport.call(
                client,
                promptTemplates.summarizerSystem(),
                userPrompt,
                VerificationOutput.class,
                agentProperties.getSummarizer().getMaxAttempts(),
                "summarizer-verification",
                this::validateVerificationOutput
        );
        return Verification.builder()
                .status("VERIFIED")
                .verifiedBy(provider.name() + ":" + result.getModel())
                .verifiedVerdict(result.getOutput().getVerifiedVerdict())
                .verifiedReason(result.getOutput().getVerifiedReason())
                .comment(result.getOutput().getVerifiedReason())
                .build();
    }

    /** 复核输出必须有 verifiedVerdict，否则视为无效让上层重试。 */
    private VerificationOutput validateVerificationOutput(VerificationOutput output) {
        if (output == null || output.getVerifiedVerdict() == null) {
            throw new IllegalArgumentException("Verification output must contain verifiedVerdict");
        }
        return output;
    }

    /**
     * 整体 Verdict 决策表：
     * <ul>
     *   <li>任一"不安全信号"成立 → PENDING_REVIEW（无任务、有冲突、有失败/跳过任务、未锚定比例超阈值、有 PENDING finding）。</li>
     *   <li>否则有任意 FAIL → FAIL。</li>
     *   <li>剩余情况（全 PASS 且没有降级信号）→ PASS。</li>
     * </ul>
     * 默认偏保守，宁可挂 PENDING_REVIEW 让人工复核也不轻易给 PASS。
     */
    private Verdict decideVerdict(
            List<Finding> findings,
            List<ConflictGroup> conflicts,
            List<ReviewTask> failedTasks,
            List<ReviewTask> skippedTasks,
            int totalTasks,
            double unanchoredRate) {
        if (totalTasks == 0
                || !conflicts.isEmpty()
                || !failedTasks.isEmpty()
                || !skippedTasks.isEmpty()
                || unanchoredRate > agentProperties.getUnanchoredPendingThreshold()
                || findings.stream().anyMatch(finding -> finding.getVerdict() == Verdict.PENDING_REVIEW)) {
            return Verdict.PENDING_REVIEW;
        }
        if (findings.stream().anyMatch(finding -> finding.getVerdict() == Verdict.FAIL)) {
            return Verdict.FAIL;
        }
        return Verdict.PASS;
    }

    /**
     * 把可能存在的多个降级原因拼成一段中文 reason，给报告 reason 字段使用。
     * 顺序固定，便于前端按已知规则切分展示。
     */
    private String buildReason(
            String baseReason,
            List<Finding> findings,
            List<ConflictGroup> conflicts,
            List<ReviewTask> failedTasks,
            List<ReviewTask> skippedTasks,
            int totalTasks,
            double unanchoredRate) {
        List<String> reasons = new ArrayList<>();
        if (baseReason != null && !baseReason.isBlank()) {
            reasons.add(baseReason);
        }
        if (totalTasks == 0) {
            reasons.add("无法识别审核任务");
        }
        if (!failedTasks.isEmpty()) {
            reasons.add("存在失败任务: " + taskIds(failedTasks));
        }
        if (!skippedTasks.isEmpty()) {
            reasons.add("存在跳过任务: " + taskIds(skippedTasks));
        }
        if (!conflicts.isEmpty()) {
            reasons.add("检测到结论冲突: " + conflicts.size());
        }
        if (unanchoredRate > agentProperties.getUnanchoredPendingThreshold()) {
            reasons.add("未锚定证据比例过高: " + unanchoredRate);
        }
        if (findings.stream().anyMatch(finding -> finding.getVerdict() == Verdict.PENDING_REVIEW)) {
            reasons.add("存在需要人工复核的 Finding");
        }
        return String.join("; ", reasons);
    }

    /**
     * 统计未锚定的 Finding 数量：既没有具体实体 ID，也没有合法 bounding box（>=4 维）。
     * 比例过高说明 LLM 在"凭印象做结论"，整体 Verdict 会被压回 PENDING_REVIEW。
     */
    private int countUnanchored(List<Finding> findings) {
        int count = 0;
        for (Finding finding : findings) {
            boolean hasEntity = finding.getEvidenceEntityIds() != null && !finding.getEvidenceEntityIds().isEmpty();
            boolean hasBoundingBox = finding.getBoundingBox() != null && finding.getBoundingBox().size() >= 4;
            if (!hasEntity && !hasBoundingBox) {
                count++;
            }
        }
        return count;
    }

    /** 抽出 task 列表里非空的 taskId 集合。 */
    private List<String> taskIds(List<ReviewTask> tasks) {
        return tasks.stream()
                .map(ReviewTask::getTaskId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    /**
     * 收集所有"被跳过"的规则 ID：合并 Orchestrator 显式传入的列表 + 跳过任务自身挂载的 ruleIds。
     * 用 LinkedHashSet 去重，保留首次出现顺序，让前端展示稳定。
     */
    private List<String> collectSkippedRuleIds(List<ReviewTask> skippedTasks, List<String> explicitSkippedRuleIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (explicitSkippedRuleIds != null) {
            ids.addAll(explicitSkippedRuleIds);
        }
        for (ReviewTask task : skippedTasks) {
            if (task.getRuleIds() != null) {
                ids.addAll(task.getRuleIds());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 两条 finding 的 evidenceEntityIds 是否有交集。 */
    private boolean entityOverlap(Finding left, Finding right) {
        if (left.getEvidenceEntityIds() == null || right.getEvidenceEntityIds() == null) {
            return false;
        }
        Set<String> leftIds = new LinkedHashSet<>(left.getEvidenceEntityIds());
        leftIds.retainAll(right.getEvidenceEntityIds());
        return !leftIds.isEmpty();
    }

    /**
     * 标准 AABB 矩形重叠判定（[minX,minY,maxX,maxY] 共 4 维）。
     * 仅在双方都给出合法 bbox 时才比较，否则直接视为不重叠。
     */
    private boolean boundingBoxOverlap(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.size() < 4 || right.size() < 4) {
            return false;
        }
        double leftMinX = left.get(0);
        double leftMinY = left.get(1);
        double leftMaxX = left.get(2);
        double leftMaxY = left.get(3);
        double rightMinX = right.get(0);
        double rightMinY = right.get(1);
        double rightMaxX = right.get(2);
        double rightMaxY = right.get(3);
        return leftMinX <= rightMaxX
                && leftMaxX >= rightMinX
                && leftMinY <= rightMaxY
                && leftMaxY >= rightMinY;
    }

    /** 选择 ConflictGroup 的 areaId：相同优先用，否则用左侧不为空的那个，再否则用右侧的。 */
    private String resolveConflictArea(Finding left, Finding right) {
        if (sameNonBlank(left.getAreaId(), right.getAreaId())) {
            return left.getAreaId();
        }
        return left.getAreaId() != null ? left.getAreaId() : right.getAreaId();
    }

    private boolean sameNonBlank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    private List<ReviewTask> nullToList(List<ReviewTask> value) {
        return value == null ? List.of() : value;
    }

    /**
     * 二次复核 LLM 的输出契约：必须给出独立的 verdict 与原因。
     * 这里不再要求 LLM 重新提供证据 —— 证据信息已经在 Finding 里，复核只校验"结论是否合理"。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerificationOutput {
        private Verdict verifiedVerdict;
        private String verifiedReason;
    }
}

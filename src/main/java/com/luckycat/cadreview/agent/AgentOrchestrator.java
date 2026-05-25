package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.parser.CadParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 多 Agent 评审编排器，负责把一次 CAD 审核请求拆成
 * 解析 → Dispatcher 分派 → Reviewer 并行审核 → Summarizer 汇总 四个阶段。
 *
 * <p>整个流程共享一个由 {@link AgentProperties#getTotalTimeoutSeconds()} 决定的总超时预算（deadline）：
 * Dispatcher 用掉一部分，Reviewer 在剩余时间里并行执行，
 * 最后给 Summarizer 预留 {@link AgentProperties.Summarizer#getReserveSeconds()} 秒。
 * 任何阶段失败或超时都不会让整次评审中断，而是降级为一份带 partial=true 的报告。
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private final CadParserService cadParserService;
    private final DispatcherAgent dispatcherAgent;
    private final ReviewerAgent reviewerAgent;
    private final SummarizerAgent summarizerAgent;
    private final IrViewService irViewService;
    private final TaskContextBuilder taskContextBuilder;
    private final EvidenceRepairService evidenceRepairService;
    private final AgentProperties agentProperties;
    private final Executor reviewerTaskExecutor;
    private final ObjectMapper objectMapper;

    public AgentOrchestrator(
            CadParserService cadParserService,
            DispatcherAgent dispatcherAgent,
            ReviewerAgent reviewerAgent,
            SummarizerAgent summarizerAgent,
            IrViewService irViewService,
            TaskContextBuilder taskContextBuilder,
            EvidenceRepairService evidenceRepairService,
            AgentProperties agentProperties,
            @Qualifier("reviewerTaskExecutor") Executor reviewerTaskExecutor,
            ObjectMapper objectMapper) {
        this.cadParserService = cadParserService;
        this.dispatcherAgent = dispatcherAgent;
        this.reviewerAgent = reviewerAgent;
        this.summarizerAgent = summarizerAgent;
        this.irViewService = irViewService;
        this.taskContextBuilder = taskContextBuilder;
        this.evidenceRepairService = evidenceRepairService;
        this.agentProperties = agentProperties;
        this.reviewerTaskExecutor = reviewerTaskExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次完整的图纸评审。
     *
     * <p>无论中途哪个阶段出问题（规则为空、Dispatcher 超时、任务超量、Reviewer 失败），
     * 都会走到 {@link #summarize} 生成一份"尽力而为"的报告，调用方只需关心返回值。
     *
     * @param file    上传的 .dxf / .dwg 文件
     * @param ruleSet 逗号分隔的规则 ID 集合，传 null 或 "all" 表示使用全部启用规则
     */
    public ReviewReport executeReview(MultipartFile file, String ruleSet) {
        long start = System.currentTimeMillis();
        // 全流程共享的截止时间，用于在各阶段动态压缩超时预算
        long deadline = start + TimeUnit.SECONDS.toMillis(agentProperties.getTotalTimeoutSeconds());
        JsonNode drawingIr = parseDrawing(file);
        List<ReviewRule> rules = agentProperties.selectRules(ruleSet);
        if (rules.isEmpty()) {
            return summarize(start, "未配置可用审核规则", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        DispatchLoopResult loopResult = runDispatchLoop(drawingIr, rules, deadline);
        return summarize(
                start,
                loopResult.reason(),
                loopResult.findings(),
                loopResult.succeededTasks(),
                loopResult.failedTasks(),
                loopResult.skippedTasks(),
                loopResult.skippedRuleIds()
        );
    }

    /**
     * 多轮调度入口：Dispatcher 每轮根据当前运行状态决定下一步是继续 Reviewer，还是进入 Summarizer。
     */
    public DispatchLoopResult runDispatchLoop(JsonNode drawingIr, List<ReviewRule> rules, long deadline) {
        return runDispatchLoop(drawingIr, rules, deadline, DispatchLoopObserver.noop());
    }

    /**
     * 多轮调度入口：带观察者回调，调用方可在每轮调度和审核后记录任务状态。
     */
    public DispatchLoopResult runDispatchLoop(
            JsonNode drawingIr,
            List<ReviewRule> rules,
            long deadline,
            DispatchLoopObserver observer) {
        return runDispatchLoop(null, drawingIr, rules, deadline, observer);
    }

    public DispatchLoopResult runDispatchLoop(
            String runId,
            JsonNode drawingIr,
            List<ReviewRule> rules,
            long deadline,
            DispatchLoopObserver observer) {
        JsonNode summary = irViewService.buildSummary(drawingIr);
        DispatchLoopState state = new DispatchLoopState();
        DispatchLoopObserver loopObserver = observer == null ? DispatchLoopObserver.noop() : observer;
        int maxRounds = Math.max(1, agentProperties.getMaxDispatchRounds());
        for (int round = 1; round <= maxRounds; round++) {
            if (remainingTaskSlots(state) <= 0) {
                state.reason = "达到最大审核任务数，强制进入汇总";
                return state.toResult();
            }
            DispatcherAgent.DispatchRoundOutput dispatch;
            try {
                dispatch = dispatchRoundWithTimeout(summary, rules, state.toJson(objectMapper, round), deadline);
            } catch (Exception ex) {
                state.reason = "Dispatcher 第 " + round + " 轮调度失败: " + readableMessage(ex);
                return state.toResult();
            }
            state.lastDispatcherReason = dispatch.getReason();
            if (dispatch.getNextAgent() == DispatcherNextAgent.SUMMARIZER) {
                state.reason = dispatch.getReason();
                return state.toResult();
            }
            List<ReviewTask> newTasks = filterDuplicateTasks(dispatch.getTasks(), state);
            DispatchPlan plan = limitTasks(newTasks, Math.max(0, remainingTaskSlots(state)));
            state.skippedTasks.addAll(plan.skippedTasks());
            loopObserver.onSkipped(plan.skippedTasks());
            if (plan.executableTasks().isEmpty()) {
                state.reason = dispatch.getReason() != null ? dispatch.getReason() : "Dispatcher 未生成新的可执行任务";
                return state.toResult();
            }
            loopObserver.onDispatched(plan.executableTasks());
            loopObserver.onRunning(plan.executableTasks());
            ReviewRunResult reviewRunResult = reviewTasks(
                    runId,
                    plan.executableTasks(),
                    rules,
                    drawingIr,
                    deadline,
                    loopObserver,
                    state.repairAttemptedKeys);
            state.findings.addAll(reviewRunResult.findings());
            state.succeededTasks.addAll(reviewRunResult.succeededTasks());
            state.failedTasks.addAll(reviewRunResult.failedTasks());
            loopObserver.onSucceeded(reviewRunResult.succeededTasks());
            loopObserver.onFailed(reviewRunResult.failedTasks(), reviewRunResult.failedReasons());
            if (remainingTaskSlots(state) <= 0) {
                state.reason = "达到最大审核任务数，强制进入汇总";
                return state.toResult();
            }
        }
        state.reason = "达到最大 Dispatcher 调度轮次，强制进入汇总";
        return state.toResult();
    }

    /**
     * 调试用入口：只跑解析 + Dispatcher，不执行实际 Review，便于前端预览拆出的任务清单。
     */
    public List<ReviewTask> dispatchOnly(MultipartFile file, String ruleSet) {
        JsonNode drawingIr = parseDrawing(file);
        List<ReviewRule> rules = agentProperties.selectRules(ruleSet);
        JsonNode summary = irViewService.buildSummary(drawingIr);
        return dispatcherAgent.dispatch(summary, rules);
    }

    /**
     * 把 Dispatcher 调用包成可超时取消的异步任务。
     * 超时上限取 dispatcher.timeoutSeconds 与剩余 deadline 的较小值，
     * 不足 5 秒则直接拒绝执行——避免 LLM 还没起步就被打断造成空转计费。
     */
    public List<ReviewTask> dispatchWithTimeout(JsonNode drawingIr, List<ReviewRule> rules, long deadline) throws Exception {
        JsonNode summary = irViewService.buildSummary(drawingIr);
        return dispatchRoundWithTimeout(summary, rules, null, deadline).getTasks();
    }

    public DispatcherAgent.DispatchRoundOutput dispatchRoundWithTimeout(
            JsonNode irSummary,
            List<ReviewRule> rules,
            JsonNode runState,
            long deadline) throws Exception {
        long timeoutMs = Math.min(
                TimeUnit.SECONDS.toMillis(agentProperties.getDispatcher().getTimeoutSeconds()),
                deadline - System.currentTimeMillis());
        if (timeoutMs < 5000) {
            throw new TimeoutException("Dispatcher timeout budget is too small");
        }
        CompletableFuture<DispatcherAgent.DispatchRoundOutput> future = CompletableFuture.supplyAsync(
                () -> dispatcherAgent.dispatchRound(irSummary, rules, runState),
                reviewerTaskExecutor);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private int remainingTaskSlots(DispatchLoopState state) {
        int maxReviewTasks = agentProperties.getMaxReviewTasks();
        if (maxReviewTasks <= 0) {
            return Integer.MAX_VALUE;
        }
        return maxReviewTasks
                - state.succeededTasks.size()
                - state.failedTasks.size()
                - state.skippedTasks.size();
    }

    private List<ReviewTask> filterDuplicateTasks(List<ReviewTask> tasks, DispatchLoopState state) {
        LinkedHashSet<String> seenKeys = state.coveredTaskKeys();
        List<ReviewTask> result = new ArrayList<>();
        for (ReviewTask task : tasks == null ? List.<ReviewTask>of() : tasks) {
            String key = taskCoverageKey(task);
            if (seenKeys.add(key)) {
                result.add(task);
            } else {
                log.info("Skip duplicated review task {} by coverage key {}", task.getTaskId(), key);
            }
        }
        return result;
    }

    /**
     * 把所有 ReviewTask 投递给 Reviewer 线程池并行跑，再按提交顺序逐一收割结果。
     *
     * <p>每个任务的超时上限会动态扣掉给 Summarizer 预留的时间，
     * 保证即使所有 Reviewer 都跑满，Summarizer 仍有窗口完成汇总。
     */
    public ReviewRunResult reviewTasks(List<ReviewTask> tasks, List<ReviewRule> rules, JsonNode drawingIr, long deadline) {
        return reviewTasks(null, tasks, rules, drawingIr, deadline, DispatchLoopObserver.noop());
    }

    public ReviewRunResult reviewTasks(
            String runId,
            List<ReviewTask> tasks,
            List<ReviewRule> rules,
            JsonNode drawingIr,
            long deadline,
            DispatchLoopObserver observer) {
        return reviewTasks(runId, tasks, rules, drawingIr, deadline, observer, new LinkedHashSet<>());
    }

    private ReviewRunResult reviewTasks(
            String runId,
            List<ReviewTask> tasks,
            List<ReviewRule> rules,
            JsonNode drawingIr,
            long deadline,
            DispatchLoopObserver observer,
            Set<String> repairAttemptedKeys) {
        Map<String, ReviewRule> ruleMap = agentProperties.ruleMap(rules);
        Map<ReviewTask, CompletableFuture<List<Finding>>> futures = new LinkedHashMap<>();
        Map<ReviewTask, List<Finding>> findingsByTask = new LinkedHashMap<>();
        List<ReviewTask> failedTasks = new ArrayList<>();
        Map<String, String> failedReasons = new LinkedHashMap<>();
        for (ReviewTask task : tasks) {
            try {
                List<ReviewRule> taskRules = resolveTaskRules(task, ruleMap);
                // Reviewer 输入由任务级上下文构建器再次清洗，避免把整张图的 entities 带进去。
                JsonNode relevantIr = taskContextBuilder.build(drawingIr, task, taskRules);
                futures.put(task, CompletableFuture.supplyAsync(
                        () -> reviewerAgent.review(task, relevantIr, taskRules),
                        reviewerTaskExecutor));
            } catch (Exception ex) {
                failedTasks.add(task);
                String reason = "Reviewer 任务构建失败: " + readableThrowable(ex);
                failedReasons.put(task.getTaskId(), reason);
                log.warn("Reviewer task {} could not be scheduled: {}", task.getTaskId(), reason);
            }
        }

        List<Finding> findings = new ArrayList<>();
        List<ReviewTask> succeededTasks = new ArrayList<>();
        for (Map.Entry<ReviewTask, CompletableFuture<List<Finding>>> entry : futures.entrySet()) {
            ReviewTask task = entry.getKey();
            CompletableFuture<List<Finding>> future = entry.getValue();
            // 剩余可用时间 = deadline 剩余 - 给 Summarizer 预留的时间
            long remainingMs = deadline - System.currentTimeMillis()
                    - TimeUnit.SECONDS.toMillis(agentProperties.getSummarizer().getReserveSeconds());
            long timeoutMs = Math.min(
                    TimeUnit.SECONDS.toMillis(agentProperties.getReviewer().getTimeoutSeconds()),
                    remainingMs);
            if (timeoutMs < 1000) {
                // 时间已经不够再跑一个 Reviewer，直接判失败让 Summarizer 收尾
                future.cancel(true);
                failedTasks.add(task);
                failedReasons.put(task.getTaskId(), "Reviewer 剩余时间不足，未执行");
                continue;
            }
            try {
                List<Finding> taskFindings = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (taskFindings != null) {
                    findings.addAll(taskFindings);
                    findingsByTask.put(task, new ArrayList<>(taskFindings));
                } else {
                    findingsByTask.put(task, new ArrayList<>());
                }
                succeededTasks.add(task);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                failedTasks.add(task);
                failedReasons.put(task.getTaskId(), "Reviewer 执行被中断");
            } catch (ExecutionException | TimeoutException ex) {
                future.cancel(true);
                failedTasks.add(task);
                String reason = reviewerFailureReason(ex);
                failedReasons.put(task.getTaskId(), reason);
                log.warn("Reviewer task {} failed: {}", task.getTaskId(), reason);
            }
        }
        RepairReviewResult repaired = repairAndRerunPendingTasks(
                runId,
                findingsByTask,
                ruleMap,
                drawingIr,
                deadline,
                observer == null ? DispatchLoopObserver.noop() : observer,
                repairAttemptedKeys == null ? new LinkedHashSet<>() : repairAttemptedKeys);
        return new ReviewRunResult(repaired.findings(), succeededTasks, failedTasks, failedReasons);
    }

    private RepairReviewResult repairAndRerunPendingTasks(
            String runId,
            Map<ReviewTask, List<Finding>> findingsByTask,
            Map<String, ReviewRule> ruleMap,
            JsonNode drawingIr,
            long deadline,
            DispatchLoopObserver observer,
            Set<String> repairAttemptedKeys) {
        if (!agentProperties.getEvidenceRepair().isEnabled() || findingsByTask.isEmpty()) {
            return flatten(findingsByTask);
        }
        int repairedCount = 0;
        int maxRepairTasks = Math.max(0, agentProperties.getEvidenceRepair().getMaxRepairTasksPerRun());
        for (Map.Entry<ReviewTask, List<Finding>> entry : findingsByTask.entrySet()) {
            if (maxRepairTasks > 0 && repairedCount >= maxRepairTasks) {
                break;
            }
            ReviewTask task = entry.getKey();
            List<Finding> taskFindings = entry.getValue();
            String repairKey = taskCoverageKey(task);
            if (!hasPendingFinding(taskFindings) || repairAttemptedKeys.contains(repairKey) || !hasRepairBudget(deadline)) {
                continue;
            }
            List<ReviewRule> taskRules;
            try {
                taskRules = resolveTaskRules(task, ruleMap);
            } catch (Exception ex) {
                log.warn("Skip evidence repair for task {}: {}", task.getTaskId(), readableThrowable(ex));
                continue;
            }
            repairAttemptedKeys.add(repairKey);
            observer.onRepairStarted(task);
            EvidenceRepairResult repair = evidenceRepairService.repair(runId, drawingIr, task, taskRules, taskFindings, 1);
            if (!repair.hasUsefulEvidence()) {
                observer.onRepairFinished(task, repair, false);
                continue;
            }
            try {
                JsonNode repairedContext = taskContextBuilder.buildWithEvidencePack(
                        drawingIr,
                        task,
                        taskRules,
                        repair.getEvidencePack());
                List<Finding> rerunFindings = rerunReviewerWithTimeout(task, repairedContext, taskRules, deadline);
                if (rerunFindings != null && !rerunFindings.isEmpty()) {
                    findingsByTask.put(task, new ArrayList<>(rerunFindings));
                    repairedCount++;
                    observer.onRepairFinished(task, repair, true);
                    log.info("Evidence repair reran reviewer for task {} with {} evidence items",
                            task.getTaskId(),
                            repair.getEvidencePack().getFoundEvidence() == null ? 0 : repair.getEvidencePack().getFoundEvidence().size());
                } else {
                    observer.onRepairFinished(task, repair, false);
                }
            } catch (Exception ex) {
                observer.onRepairFinished(task, repair, false);
                log.warn("Evidence repair rerun failed for task {}: {}", task.getTaskId(), readableThrowable(ex));
            }
        }
        return flatten(findingsByTask);
    }

    private List<Finding> rerunReviewerWithTimeout(
            ReviewTask task,
            JsonNode repairedContext,
            List<ReviewRule> taskRules,
            long deadline) throws Exception {
        long remainingMs = deadline - System.currentTimeMillis()
                - TimeUnit.SECONDS.toMillis(agentProperties.getSummarizer().getReserveSeconds());
        long timeoutMs = Math.min(
                TimeUnit.SECONDS.toMillis(agentProperties.getReviewer().getTimeoutSeconds()),
                remainingMs);
        if (timeoutMs < 1000) {
            throw new TimeoutException("Reviewer rerun timeout budget is too small");
        }
        CompletableFuture<List<Finding>> future = CompletableFuture.supplyAsync(
                () -> reviewerAgent.review(task, repairedContext, taskRules),
                reviewerTaskExecutor);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private boolean hasRepairBudget(long deadline) {
        long remainingMs = deadline - System.currentTimeMillis()
                - TimeUnit.SECONDS.toMillis(agentProperties.getSummarizer().getReserveSeconds());
        long minimumRepairMs = Math.min(
                TimeUnit.SECONDS.toMillis(agentProperties.getReviewer().getTimeoutSeconds()),
                TimeUnit.SECONDS.toMillis(30));
        return remainingMs > minimumRepairMs;
    }

    private boolean hasPendingFinding(List<Finding> findings) {
        return findings != null && findings.stream()
                .anyMatch(finding -> finding.getVerdict() == com.luckycat.cadreview.dto.enums.Verdict.PENDING_REVIEW
                        && !Boolean.FALSE.equals(finding.getRepairable()));
    }

    private RepairReviewResult flatten(Map<ReviewTask, List<Finding>> findingsByTask) {
        List<Finding> findings = new ArrayList<>();
        for (List<Finding> taskFindings : findingsByTask.values()) {
            if (taskFindings != null) {
                findings.addAll(taskFindings);
            }
        }
        return new RepairReviewResult(findings);
    }

    /**
     * 把 task 上挂的 ruleIds 解析成实际的 ReviewRule 对象列表；
     * 任务若没有任何匹配规则会直接抛错——Dispatcher 已做过校验，到这里仍为空说明规则配置异常。
     */
    private List<ReviewRule> resolveTaskRules(ReviewTask task, Map<String, ReviewRule> ruleMap) {
        List<ReviewRule> taskRules = new ArrayList<>();
        for (String ruleId : task.getRuleIds() == null ? List.<String>of() : task.getRuleIds()) {
            ReviewRule rule = ruleMap.get(ruleId);
            if (rule != null) {
                taskRules.add(rule);
            }
        }
        if (taskRules.isEmpty()) {
            throw new IllegalArgumentException("No rules found for task " + task.getTaskId());
        }
        return taskRules;
    }

    /**
     * 统一汇总入口。
     * partial 标志只要存在 reason / 失败任务 / 跳过任务任一情况就为 true，
     * 让前端可以明确区分"完整结果"与"降级结果"。
     */
    public ReviewReport summarize(
            long start,
            String reason,
            List<Finding> findings,
            List<ReviewTask> succeededTasks,
            List<ReviewTask> failedTasks,
            List<ReviewTask> skippedTasks,
            List<String> skippedRuleIds) {
        long durationMs = System.currentTimeMillis() - start;
        ReviewSummaryInput input = ReviewSummaryInput.builder()
                .findings(findings)
                .succeededTasks(succeededTasks)
                .failedTasks(failedTasks)
                .skippedTasks(skippedTasks)
                .skippedRuleIds(skippedRuleIds)
                .reason(reason)
                .durationMs(durationMs)
                .partial(reason != null || !failedTasks.isEmpty() || !skippedTasks.isEmpty())
                .build();
        return summarizerAgent.summarize(input);
    }

    /**
     * 根据文件后缀分发到 dwg / dxf 解析器，并把结果转成统一的 JsonNode IR。
     */
    public JsonNode parseDrawing(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file must have a filename");
        }
        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".dwg")) {
            return objectMapper.valueToTree(cadParserService.parseDwg(file));
        }
        if (lowerName.endsWith(".dxf")) {
            return objectMapper.valueToTree(cadParserService.parseDxf(file));
        }
        throw new IllegalArgumentException("Only .dxf and .dwg files are supported");
    }

    private String readableMessage(Exception ex) {
        if (ex instanceof TimeoutException) {
            return "Dispatcher 超时，超过 " + agentProperties.getDispatcher().getTimeoutSeconds() + " 秒未返回";
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String reviewerFailureReason(Exception ex) {
        if (ex instanceof TimeoutException) {
            return "Reviewer 超时，超过 " + agentProperties.getReviewer().getTimeoutSeconds() + " 秒未返回";
        }
        Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        return "Reviewer 执行异常: " + readableThrowable(cause);
    }

    private String readableThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String taskCoverageKey(ReviewTask task) {
        String ruleKey = task.getRuleIds() == null || task.getRuleIds().isEmpty()
                ? normalizeKey(task.getCheckItem())
                : normalizeKey(String.join(",", task.getRuleIds()));
        return ruleKey + "|" + normalizeKey(task.getAreaId());
    }

    private static String normalizeKey(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 按 Dispatcher 给出的优先级排序后，截断到 maxReviewTasks。
     * 截断的部分作为 skippedTasks 返回，调用方据此在报告里标记"未执行"。
     */
    public static DispatchPlan limitTasks(List<ReviewTask> tasks, int maxReviewTasks) {
        List<ReviewTask> sorted = new ArrayList<>(tasks == null ? List.of() : tasks);
        sorted.sort(DispatcherAgent.taskComparator());
        if (maxReviewTasks <= 0 || sorted.size() <= maxReviewTasks) {
            return new DispatchPlan(sorted, List.of());
        }
        return new DispatchPlan(
                new ArrayList<>(sorted.subList(0, maxReviewTasks)),
                new ArrayList<>(sorted.subList(maxReviewTasks, sorted.size())));
    }

    /**
     * 任务分派计划：可执行任务 + 因数量上限被跳过的任务。
     */
    public record DispatchPlan(List<ReviewTask> executableTasks, List<ReviewTask> skippedTasks) {
        public List<String> skippedTaskIds() {
            return skippedTasks.stream().map(ReviewTask::getTaskId).toList();
        }

        public List<String> skippedRuleIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (ReviewTask task : skippedTasks) {
                if (task.getRuleIds() != null) {
                    ids.addAll(task.getRuleIds());
                }
            }
            return new ArrayList<>(ids);
        }
    }

    /** Reviewer 阶段的执行结果：findings 与成功/失败的任务三元组。 */
    public record ReviewRunResult(
            List<Finding> findings,
            List<ReviewTask> succeededTasks,
            List<ReviewTask> failedTasks,
            Map<String, String> failedReasons) {
    }

    /** 多轮 Dispatcher 调度后的整体结果。 */
    public record DispatchLoopResult(
            List<Finding> findings,
            List<ReviewTask> succeededTasks,
            List<ReviewTask> failedTasks,
            List<ReviewTask> skippedTasks,
            String reason) {

        public List<String> skippedRuleIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (ReviewTask task : skippedTasks == null ? List.<ReviewTask>of() : skippedTasks) {
                if (task.getRuleIds() != null) {
                    ids.addAll(task.getRuleIds());
                }
            }
            return new ArrayList<>(ids);
        }
    }

    /** 多轮调度过程中的任务状态观察者。 */
    public interface DispatchLoopObserver {
        default void onDispatched(List<ReviewTask> tasks) {
        }

        default void onRunning(List<ReviewTask> tasks) {
        }

        default void onSucceeded(List<ReviewTask> tasks) {
        }

        default void onFailed(List<ReviewTask> tasks) {
        }

        default void onFailed(List<ReviewTask> tasks, Map<String, String> reasons) {
            onFailed(tasks);
        }

        default void onSkipped(List<ReviewTask> tasks) {
        }

        default void onRepairStarted(ReviewTask task) {
        }

        default void onRepairFinished(ReviewTask task, EvidenceRepairResult repairResult, boolean rerunSucceeded) {
        }

        static DispatchLoopObserver noop() {
            return new DispatchLoopObserver() {
            };
        }
    }

    private record RepairReviewResult(List<Finding> findings) {
    }

    private static class DispatchLoopState {
        private final List<Finding> findings = new ArrayList<>();
        private final List<ReviewTask> succeededTasks = new ArrayList<>();
        private final List<ReviewTask> failedTasks = new ArrayList<>();
        private final List<ReviewTask> skippedTasks = new ArrayList<>();
        private final Set<String> repairAttemptedKeys = new LinkedHashSet<>();
        private String reason;
        private String lastDispatcherReason;

        private ObjectNode toJson(ObjectMapper objectMapper, int nextRound) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("nextRound", nextRound);
            node.set("findings", objectMapper.valueToTree(findings));
            node.set("succeededTasks", objectMapper.valueToTree(succeededTasks));
            node.set("failedTasks", objectMapper.valueToTree(failedTasks));
            node.set("skippedTasks", objectMapper.valueToTree(skippedTasks));
            node.set("repairAttemptedKeys", objectMapper.valueToTree(repairAttemptedKeys));
            node.put("lastDispatcherReason", lastDispatcherReason);
            return node;
        }

        private LinkedHashSet<String> coveredTaskKeys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (ReviewTask task : succeededTasks) {
                keys.add(taskCoverageKey(task));
            }
            for (ReviewTask task : failedTasks) {
                keys.add(taskCoverageKey(task));
            }
            for (ReviewTask task : skippedTasks) {
                keys.add(taskCoverageKey(task));
            }
            return keys;
        }

        private DispatchLoopResult toResult() {
            return new DispatchLoopResult(
                    new ArrayList<>(findings),
                    new ArrayList<>(succeededTasks),
                    new ArrayList<>(failedTasks),
                    new ArrayList<>(skippedTasks),
                    reason);
        }
    }
}

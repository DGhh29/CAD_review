package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class AgentOrchestrator {

    private final CadParserService cadParserService;
    private final DispatcherAgent dispatcherAgent;
    private final ReviewerAgent reviewerAgent;
    private final SummarizerAgent summarizerAgent;
    private final IrViewService irViewService;
    private final AgentProperties agentProperties;
    private final Executor reviewerTaskExecutor;

    public AgentOrchestrator(
            CadParserService cadParserService,
            DispatcherAgent dispatcherAgent,
            ReviewerAgent reviewerAgent,
            SummarizerAgent summarizerAgent,
            IrViewService irViewService,
            AgentProperties agentProperties,
            @Qualifier("reviewerTaskExecutor") Executor reviewerTaskExecutor) {
        this.cadParserService = cadParserService;
        this.dispatcherAgent = dispatcherAgent;
        this.reviewerAgent = reviewerAgent;
        this.summarizerAgent = summarizerAgent;
        this.irViewService = irViewService;
        this.agentProperties = agentProperties;
        this.reviewerTaskExecutor = reviewerTaskExecutor;
    }

    public ReviewReport executeReview(MultipartFile file, String ruleSet) {
        long start = System.currentTimeMillis();
        long deadline = start + TimeUnit.SECONDS.toMillis(agentProperties.getTotalTimeoutSeconds());
        JsonNode drawingIr = parseDrawing(file);
        List<ReviewRule> rules = agentProperties.selectRules(ruleSet);
        if (rules.isEmpty()) {
            return summarize(start, "未配置可用审核规则", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<ReviewTask> dispatchedTasks;
        try {
            dispatchedTasks = dispatchWithTimeout(drawingIr, rules, deadline);
        } catch (Exception ex) {
            log.warn("Dispatcher failed: {}", ex.getMessage());
            return summarize(start, "Dispatcher 分派失败: " + ex.getMessage(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if (dispatchedTasks.isEmpty()) {
            return summarize(start, "无法识别审核任务", List.of(), List.of(), List.of(), List.of(), List.of());
        }

        DispatchPlan plan = limitTasks(dispatchedTasks, agentProperties.getMaxReviewTasks());
        if (plan.executableTasks().isEmpty()) {
            return summarize(start, "任务数量超过上限，未执行评审", List.of(), List.of(), List.of(), plan.skippedTasks(), plan.skippedRuleIds());
        }

        ReviewRunResult reviewRunResult = reviewTasks(plan.executableTasks(), rules, drawingIr, deadline);
        return summarize(
                start,
                null,
                reviewRunResult.findings(),
                reviewRunResult.succeededTasks(),
                reviewRunResult.failedTasks(),
                plan.skippedTasks(),
                plan.skippedRuleIds()
        );
    }

    public List<ReviewTask> dispatchOnly(MultipartFile file, String ruleSet) {
        JsonNode drawingIr = parseDrawing(file);
        List<ReviewRule> rules = agentProperties.selectRules(ruleSet);
        JsonNode summary = irViewService.buildSummary(drawingIr);
        return dispatcherAgent.dispatch(summary, rules);
    }

    private List<ReviewTask> dispatchWithTimeout(JsonNode drawingIr, List<ReviewRule> rules, long deadline) throws Exception {
        JsonNode summary = irViewService.buildSummary(drawingIr);
        long timeoutMs = Math.min(
                TimeUnit.SECONDS.toMillis(agentProperties.getDispatcher().getTimeoutSeconds()),
                deadline - System.currentTimeMillis());
        if (timeoutMs < 5000) {
            throw new TimeoutException("Dispatcher timeout budget is too small");
        }
        CompletableFuture<List<ReviewTask>> future = CompletableFuture.supplyAsync(
                () -> dispatcherAgent.dispatch(summary, rules),
                reviewerTaskExecutor);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private ReviewRunResult reviewTasks(List<ReviewTask> tasks, List<ReviewRule> rules, JsonNode drawingIr, long deadline) {
        Map<String, ReviewRule> ruleMap = agentProperties.ruleMap(rules);
        Map<ReviewTask, CompletableFuture<List<Finding>>> futures = new LinkedHashMap<>();
        List<ReviewTask> failedTasks = new ArrayList<>();
        for (ReviewTask task : tasks) {
            try {
                JsonNode relevantIr = irViewService.slice(drawingIr, task.getEntityIds(), task.getLayerNames());
                List<ReviewRule> taskRules = resolveTaskRules(task, ruleMap);
                futures.put(task, CompletableFuture.supplyAsync(
                        () -> reviewerAgent.review(task, relevantIr, taskRules),
                        reviewerTaskExecutor));
            } catch (Exception ex) {
                failedTasks.add(task);
                log.warn("Reviewer task {} could not be scheduled: {}", task.getTaskId(), ex.getMessage());
            }
        }

        List<Finding> findings = new ArrayList<>();
        List<ReviewTask> succeededTasks = new ArrayList<>();
        for (Map.Entry<ReviewTask, CompletableFuture<List<Finding>>> entry : futures.entrySet()) {
            ReviewTask task = entry.getKey();
            CompletableFuture<List<Finding>> future = entry.getValue();
            long remainingMs = deadline - System.currentTimeMillis()
                    - TimeUnit.SECONDS.toMillis(agentProperties.getSummarizer().getReserveSeconds());
            long timeoutMs = Math.min(
                    TimeUnit.SECONDS.toMillis(agentProperties.getReviewer().getTimeoutSeconds()),
                    remainingMs);
            if (timeoutMs < 1000) {
                future.cancel(true);
                failedTasks.add(task);
                continue;
            }
            try {
                List<Finding> taskFindings = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (taskFindings != null) {
                    findings.addAll(taskFindings);
                }
                succeededTasks.add(task);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                failedTasks.add(task);
            } catch (ExecutionException | TimeoutException ex) {
                future.cancel(true);
                failedTasks.add(task);
                log.warn("Reviewer task {} failed: {}", task.getTaskId(), ex.getMessage());
            }
        }
        return new ReviewRunResult(findings, succeededTasks, failedTasks);
    }

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

    private ReviewReport summarize(
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

    private JsonNode parseDrawing(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file must have a filename");
        }
        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".dwg")) {
            return cadParserService.parseDwg(file);
        }
        if (lowerName.endsWith(".dxf")) {
            return cadParserService.parseDxf(file);
        }
        throw new IllegalArgumentException("Only .dxf and .dwg files are supported");
    }

    static DispatchPlan limitTasks(List<ReviewTask> tasks, int maxReviewTasks) {
        List<ReviewTask> sorted = new ArrayList<>(tasks == null ? List.of() : tasks);
        sorted.sort(DispatcherAgent.taskComparator());
        if (maxReviewTasks <= 0 || sorted.size() <= maxReviewTasks) {
            return new DispatchPlan(sorted, List.of());
        }
        return new DispatchPlan(
                new ArrayList<>(sorted.subList(0, maxReviewTasks)),
                new ArrayList<>(sorted.subList(maxReviewTasks, sorted.size())));
    }

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

    private record ReviewRunResult(
            List<Finding> findings,
            List<ReviewTask> succeededTasks,
            List<ReviewTask> failedTasks) {
    }
}

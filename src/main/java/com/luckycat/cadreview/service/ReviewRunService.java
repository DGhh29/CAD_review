package com.luckycat.cadreview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.luckycat.cadreview.agent.AgentOrchestrator;
import com.luckycat.cadreview.agent.CadIrCleaner;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.ReviewRunProperties;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewRunCreatedResponse;
import com.luckycat.cadreview.dto.ReviewRunStatus;
import com.luckycat.cadreview.dto.ReviewRunSummary;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.ReviewTaskStatus;
import com.luckycat.cadreview.repository.ReviewRunRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 异步审图运行服务。
 */
@Slf4j
@Service
public class ReviewRunService {

    private final AgentOrchestrator agentOrchestrator;
    private final AgentProperties agentProperties;
    private final CadIrCleaner cadIrCleaner;
    private final ReviewRunRepository repository;
    private final Executor reviewRunTaskExecutor;
    private final ReviewRunProperties properties;

    public ReviewRunService(
            AgentOrchestrator agentOrchestrator,
            AgentProperties agentProperties,
            CadIrCleaner cadIrCleaner,
            ReviewRunRepository repository,
            @Qualifier("reviewRunTaskExecutor") Executor reviewRunTaskExecutor,
            ReviewRunProperties properties) {
        this.agentOrchestrator = agentOrchestrator;
        this.agentProperties = agentProperties;
        this.cadIrCleaner = cadIrCleaner;
        this.repository = repository;
        this.reviewRunTaskExecutor = reviewRunTaskExecutor;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        if (properties.isInitializeSchema()) {
            repository.initializeSchema();
        }
    }

    public ReviewRunCreatedResponse create(MultipartFile file, String ruleSet) {
        String runId = UUID.randomUUID().toString();
        repository.createRun(runId, file.getOriginalFilename(), ruleSet);
        MultipartFile snapshot = snapshot(file);
        reviewRunTaskExecutor.execute(() -> execute(runId, snapshot, ruleSet));
        return ReviewRunCreatedResponse.builder()
                .runId(runId)
                .status(ReviewRunStatus.UPLOADED)
                .build();
    }

    private MultipartFile snapshot(MultipartFile file) {
        try {
            return new MultipartFileSnapshot(
                    file.getName(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取上传文件失败: " + ex.getMessage(), ex);
        }
    }

    private record MultipartFileSnapshot(
            String name,
            String originalFilename,
            String contentType,
            byte[] bytes) implements MultipartFile {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }

    public Optional<ReviewRunSummary> getSummary(String runId) {
        return repository.findSummary(runId);
    }

    public List<ReviewRunSummary> listSummaries(int limit) {
        return repository.listSummaries(limit);
    }

    public Optional<ReviewReport> getReport(String runId) {
        return repository.findReport(runId);
    }

    private void execute(String runId, MultipartFile file, String ruleSet) {
        long start = System.currentTimeMillis();
        long deadline = start + TimeUnit.SECONDS.toMillis(agentProperties.getTotalTimeoutSeconds());
        try {
            JsonNode drawingIr = agentOrchestrator.parseDrawing(file);
            repository.saveRawIr(runId, drawingIr);
            repository.updateStatus(runId, ReviewRunStatus.PARSED, null);

            JsonNode cleanContext = cadIrCleaner.buildReviewContext(drawingIr);
            repository.saveCleanContext(runId, cleanContext);
            repository.updateStatus(runId, ReviewRunStatus.CLEANED, null);

            List<ReviewRule> rules = agentProperties.selectRules(ruleSet);
            repository.updateStatus(runId, ReviewRunStatus.RULE_PLANNED, null);

            repository.updateStatus(runId, ReviewRunStatus.DISPATCHED, null);

            repository.updateStatus(runId, ReviewRunStatus.REVIEWING, null);
            AgentOrchestrator.DispatchLoopResult loopResult = agentOrchestrator.runDispatchLoop(
                    runId,
                    drawingIr,
                    rules,
                    deadline,
                    new RunTaskPersistenceObserver(runId));

            repository.updateStatus(runId, ReviewRunStatus.SUMMARIZING, null);
            ReviewReport report = agentOrchestrator.summarize(
                    start,
                    loopResult.reason(),
                    loopResult.findings(),
                    loopResult.succeededTasks(),
                    loopResult.failedTasks(),
                    loopResult.skippedTasks(),
                    loopResult.skippedRuleIds());
            repository.saveReport(runId, report);
            repository.updateStatus(runId, report.isPartial() ? ReviewRunStatus.PARTIAL : ReviewRunStatus.COMPLETED, report.getReason());
        } catch (Exception ex) {
            String reason = readableMessage(ex);
            log.warn("Review run {} failed: {}", runId, reason);
            ReviewReport report = agentOrchestrator.summarize(
                    start,
                    "异步审图失败: " + reason,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
            repository.saveReport(runId, report);
            repository.updateStatus(runId, ReviewRunStatus.FAILED, reason);
        }
    }

    private String readableMessage(Exception ex) {
        if (ex instanceof TimeoutException) {
            return "执行超时";
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private class RunTaskPersistenceObserver implements AgentOrchestrator.DispatchLoopObserver {
        private final String runId;

        private RunTaskPersistenceObserver(String runId) {
            this.runId = runId;
        }

        @Override
        public void onDispatched(List<ReviewTask> tasks) {
            tasks.forEach(task -> repository.upsertTask(runId, task, ReviewTaskStatus.DISPATCHED, null));
        }

        @Override
        public void onRunning(List<ReviewTask> tasks) {
            tasks.forEach(task -> repository.upsertTask(runId, task, ReviewTaskStatus.RUNNING, null));
        }

        @Override
        public void onSucceeded(List<ReviewTask> tasks) {
            tasks.forEach(task -> repository.upsertTask(runId, task, ReviewTaskStatus.SUCCEEDED, null));
        }

        @Override
        public void onFailed(List<ReviewTask> tasks, Map<String, String> reasons) {
            tasks.forEach(task -> repository.upsertTask(
                    runId,
                    task,
                    ReviewTaskStatus.FAILED,
                    reasons.getOrDefault(task.getTaskId(), "Reviewer 执行失败或超时")));
        }

        @Override
        public void onSkipped(List<ReviewTask> tasks) {
            tasks.forEach(task -> repository.upsertTask(runId, task, ReviewTaskStatus.SKIPPED, "任务数量超过上限"));
        }

        @Override
        public void onRepairStarted(ReviewTask task) {
            repository.upsertTask(runId, task, ReviewTaskStatus.REPAIRING, "PENDING_REVIEW 触发 raw_ir 定向补证");
        }

        @Override
        public void onRepairFinished(ReviewTask task, com.luckycat.cadreview.agent.EvidenceRepairResult repairResult, boolean rerunSucceeded) {
            String reason = repairResult == null
                    ? "补证流程未返回结果"
                    : repairResult.getStatus() + ": " + repairResult.getReason();
            repository.upsertTask(
                    runId,
                    task,
                    rerunSucceeded ? ReviewTaskStatus.SUCCEEDED : ReviewTaskStatus.REPAIRING,
                    rerunSucceeded ? "补证并定向复审完成: " + reason : "补证未形成有效复审: " + reason);
        }
    }

}

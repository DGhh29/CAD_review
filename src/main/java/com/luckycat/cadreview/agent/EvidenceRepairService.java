package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * PENDING_REVIEW 后的定向补证服务。
 *
 * <p>流程：根据 Finding + Rule 生成 EvidenceSearchTask → raw_ir 切片 →
 * 轻量抽取 → reducer 合并成 evidencePack。服务本身不做最终审核。
 */
@Slf4j
@Service
public class EvidenceRepairService {

    private final RawIrChunker rawIrChunker;
    private final EvidenceExtractionClient evidenceExtractionClient;
    private final EvidenceReducer evidenceReducer;
    private final AgentProperties agentProperties;
    private final Executor evidenceRepairTaskExecutor;

    public EvidenceRepairService(
            RawIrChunker rawIrChunker,
            EvidenceExtractionClient evidenceExtractionClient,
            EvidenceReducer evidenceReducer,
            AgentProperties agentProperties,
            @Qualifier("evidenceRepairTaskExecutor") Executor evidenceRepairTaskExecutor) {
        this.rawIrChunker = rawIrChunker;
        this.evidenceExtractionClient = evidenceExtractionClient;
        this.evidenceReducer = evidenceReducer;
        this.agentProperties = agentProperties;
        this.evidenceRepairTaskExecutor = evidenceRepairTaskExecutor;
    }

    public EvidenceRepairResult repair(
            String runId,
            JsonNode rawIr,
            ReviewTask task,
            List<ReviewRule> rules,
            List<Finding> findings,
            int attempt) {
        if (!agentProperties.getEvidenceRepair().isEnabled()) {
            return skipped("DISABLED", "Evidence repair disabled");
        }
        if (rules != null && !rules.isEmpty() && rules.stream()
                .allMatch(rule -> rule.getRepairPolicy() != null && !rule.getRepairPolicy().isEnabled())) {
            return skipped("RULE_REPAIR_DISABLED", "All task rules disabled evidence repair");
        }
        if (attempt > Math.max(1, agentProperties.getEvidenceRepair().getMaxAttemptsPerTask())) {
            return skipped("MAX_ATTEMPTS_REACHED", "Evidence repair attempts exceeded");
        }
        if (!hasRepairablePendingFinding(findings)) {
            return skipped("NOT_PENDING", "Task has no repairable PENDING_REVIEW finding");
        }
        EvidenceSearchTask searchTask = buildSearchTask(runId, task, rules, findings);
        List<EvidenceChunk> chunks = rawIrChunker.chunk(rawIr, searchTask);
        if (chunks.isEmpty()) {
            return EvidenceRepairResult.builder()
                    .attempted(true)
                    .usefulEvidence(false)
                    .status("RAW_DATA_MISSING")
                    .reason("raw_ir 中未切出匹配补证线索的候选 chunk")
                    .evidencePack(emptyPack(searchTask, attempt, "RAW_DATA_MISSING"))
                    .build();
        }

        int maxChunks = Math.min(chunks.size(), searchTask.getMaxChunks());
        List<EvidenceExtractionResult> results = extractChunks(searchTask, chunks.subList(0, maxChunks));
        TaskEvidencePack pack = evidenceReducer.reduce(searchTask, results, attempt);
        boolean useful = pack.getFoundEvidence() != null && !pack.getFoundEvidence().isEmpty();
        return EvidenceRepairResult.builder()
                .attempted(true)
                .usefulEvidence(useful)
                .status(useful ? pack.getQuality().getRepairStatus() : "RAW_DATA_MISSING")
                .reason(useful ? "已从 raw_ir 抽取到补充证据" : "已搜索 raw_ir，但未找到有效证据")
                .evidencePack(pack)
                .build();
    }

    private List<EvidenceExtractionResult> extractChunks(EvidenceSearchTask searchTask, List<EvidenceChunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        int timeoutSeconds = Math.max(1, agentProperties.getEvidenceRepair().getChunkTimeoutSeconds());
        List<CompletableFuture<EvidenceExtractionResult>> futures = new ArrayList<>();
        for (EvidenceChunk chunk : chunks) {
            futures.add(CompletableFuture
                    .supplyAsync(() -> evidenceExtractionClient.extract(searchTask, chunk), evidenceRepairTaskExecutor)
                    .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(ex -> failedExtraction(searchTask, chunk, ex)));
        }
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private EvidenceExtractionResult failedExtraction(EvidenceSearchTask searchTask, EvidenceChunk chunk, Throwable ex) {
        log.warn("Evidence extraction chunk {} for task {} failed: {}",
                chunk.getChunkId(),
                searchTask.getTaskId(),
                ex == null ? "unknown" : ex.getMessage());
        return EvidenceExtractionResult.builder()
                .taskId(searchTask.getTaskId())
                .chunkId(chunk.getChunkId())
                .relevant(false)
                .evidence(Collections.emptyList())
                .stillMissing(searchTask.getMissingEvidence())
                .build();
    }

    public EvidenceSearchTask buildSearchTask(
            String runId,
            ReviewTask task,
            List<ReviewRule> rules,
            List<Finding> findings) {
        Set<String> keywords = new LinkedHashSet<>();
        Set<String> layers = new LinkedHashSet<>();
        Set<String> missingEvidence = new LinkedHashSet<>();
        Set<String> entityTypes = new LinkedHashSet<>(List.of("TEXT", "MTEXT", "INSERT", "DIMENSION", "LINE", "LWPOLYLINE", "ARC"));

        addTaskHints(keywords, layers, missingEvidence, task);
        int maxChunks = addRuleHints(keywords, layers, missingEvidence, entityTypes, rules);
        addFindingHints(keywords, missingEvidence, findings);

        return EvidenceSearchTask.builder()
                .runId(runId)
                .taskId(task.getTaskId())
                .ruleId(primaryRuleId(task, rules))
                .category(task.getCategory())
                .missingEvidence(new ArrayList<>(missingEvidence))
                .keywords(new ArrayList<>(keywords))
                .layerHints(new ArrayList<>(layers))
                .entityTypeHints(new ArrayList<>(entityTypes))
                .maxChunks(maxChunks)
                .build();
    }

    private void addTaskHints(Set<String> keywords, Set<String> layers, Set<String> missingEvidence, ReviewTask task) {
        addKeywordText(keywords, task.getCategory());
        addKeywordText(keywords, task.getCheckItem());
        if (task.getLayerNames() != null) {
            layers.addAll(task.getLayerNames());
        }
        if (task.getEvidenceGroups() != null) {
            for (String group : task.getEvidenceGroups()) {
                addKeywordText(keywords, group);
                addMissingByGroup(missingEvidence, group);
            }
        }
    }

    private int addRuleHints(
            Set<String> keywords,
            Set<String> layers,
            Set<String> missingEvidence,
            Set<String> entityTypes,
            List<ReviewRule> rules) {
        int maxChunks = agentProperties.getEvidenceRepair().getMaxChunksPerTask();
        for (ReviewRule rule : rules == null ? List.<ReviewRule>of() : rules) {
            addKeywordText(keywords, rule.getId());
            addKeywordText(keywords, rule.getTitle());
            addKeywordText(keywords, rule.getScope());
            addKeywordText(keywords, rule.getPromptFragment());
            if (rule.getRequiredEvidence() != null) {
                missingEvidence.addAll(rule.getRequiredEvidence());
                keywords.addAll(rule.getRequiredEvidence());
            }
            if (rule.getSearchHints() != null) {
                addAll(keywords, rule.getSearchHints().getKeywords());
                addAll(layers, rule.getSearchHints().getLayers());
                addAll(entityTypes, rule.getSearchHints().getEntityTypes());
            }
            if (rule.getRepairPolicy() != null && rule.getRepairPolicy().getMaxChunks() > 0) {
                maxChunks = Math.min(maxChunks, rule.getRepairPolicy().getMaxChunks());
            }
            addMissingByText(missingEvidence, rule.getTitle() + " " + rule.getScope() + " " + rule.getPromptFragment());
            addLayerByText(layers, rule.getScope() + " " + rule.getPromptFragment());
        }
        return maxChunks;
    }

    private void addFindingHints(Set<String> keywords, Set<String> missingEvidence, List<Finding> findings) {
        for (Finding finding : findings == null ? List.<Finding>of() : findings) {
            addKeywordText(keywords, finding.getReason());
            addKeywordText(keywords, finding.getEvidenceText());
            addAll(missingEvidence, finding.getMissingEvidence());
            addAll(keywords, finding.getRepairHints());
            addMissingByText(missingEvidence, finding.getReason());
        }
    }

    private void addMissingByGroup(Set<String> missingEvidence, String group) {
        String normalized = group == null ? "" : group.toLowerCase(Locale.ROOT);
        if (normalized.contains("fire") || normalized.contains("消防")) {
            missingEvidence.addAll(List.of("消防", "消火栓", "喷淋", "报警", "消防水池", "消防水泵房"));
        }
        if (normalized.contains("parking") || normalized.contains("停车")) {
            missingEvidence.addAll(List.of("停车", "车位", "机动车", "无障碍", "非机动车", "建筑面积"));
        }
        if (normalized.contains("road") || normalized.contains("道路")) {
            missingEvidence.addAll(List.of("道路", "消防车道", "宽", "半径", "R="));
        }
        if (normalized.contains("dimension") || normalized.contains("尺寸")) {
            missingEvidence.addAll(List.of("尺寸", "宽", "净宽", "半径", "R="));
        }
    }

    private void addMissingByText(Set<String> missingEvidence, String text) {
        String value = text == null ? "" : text;
        List<String> known = List.of(
                "消防", "防火分区", "疏散", "楼梯", "通道", "净宽", "消火栓", "喷淋", "报警",
                "消防水池", "消防水泵房", "道路", "消防车道", "宽", "半径", "R=",
                "停车", "车位", "机动车", "无障碍", "非机动车", "建筑面积", "指标");
        for (String keyword : known) {
            if (value.contains(keyword)) {
                missingEvidence.add(keyword);
            }
        }
    }

    private void addLayerByText(Set<String> layers, String text) {
        String value = text == null ? "" : text;
        if (value.contains("消防")) {
            layers.addAll(List.of("EQUIP_消防", "EQUIP_消火栓", "SPACE", "PUB_TEXT"));
        }
        if (value.contains("道路") || value.contains("车道")) {
            layers.addAll(List.of("内部道路", "ROAD", "PUB_DIM", "PUB_TEXT"));
        }
        if (value.contains("停车") || value.contains("车位") || value.contains("建筑面积")) {
            layers.addAll(List.of("PUB_TEXT", "PARKING", "车位", "停车"));
        }
        if (value.contains("防火分区")) {
            layers.addAll(List.of("HATCH", "FIRE", "PUB_TEXT"));
        }
    }

    private void addKeywordText(Set<String> keywords, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String token : text.split("[,;，；、\\s/]+")) {
            if (!token.isBlank() && token.length() <= 32) {
                keywords.add(token);
            }
        }
        addMissingByText(keywords, text);
    }

    private void addAll(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value);
            }
        }
    }

    private boolean hasRepairablePendingFinding(List<Finding> findings) {
        return findings != null && findings.stream().anyMatch(finding ->
                finding.getVerdict() == Verdict.PENDING_REVIEW && !Boolean.FALSE.equals(finding.getRepairable()));
    }

    private String primaryRuleId(ReviewTask task, List<ReviewRule> rules) {
        if (task.getRuleIds() != null && !task.getRuleIds().isEmpty()) {
            return task.getRuleIds().get(0);
        }
        if (rules != null && !rules.isEmpty()) {
            return rules.get(0).getId();
        }
        return "UNKNOWN_RULE";
    }

    private EvidenceRepairResult skipped(String status, String reason) {
        return EvidenceRepairResult.builder()
                .attempted(false)
                .usefulEvidence(false)
                .status(status)
                .reason(reason)
                .build();
    }

    private TaskEvidencePack emptyPack(EvidenceSearchTask task, int attempt, String status) {
        return TaskEvidencePack.builder()
                .runId(task.getRunId())
                .taskId(task.getTaskId())
                .ruleId(task.getRuleId())
                .attempt(attempt)
                .missingEvidence(task.getMissingEvidence())
                .quality(TaskEvidencePack.Quality.builder()
                        .evidenceCount(0)
                        .sourceTraceable(true)
                        .confidence(0.0d)
                        .repairStatus(status)
                        .build())
                .build();
    }
}

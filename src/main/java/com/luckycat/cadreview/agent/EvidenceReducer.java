package com.luckycat.cadreview.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并多个 chunk 的抽取结果，负责去重、过滤低置信度和计算 evidencePack 质量。
 */
@Component
public class EvidenceReducer {

    private static final double MIN_CONFIDENCE = 0.35d;
    private static final int MAX_EVIDENCE = 80;

    public TaskEvidencePack reduce(EvidenceSearchTask task, List<EvidenceExtractionResult> results, int attempt) {
        Map<String, ExtractedEvidence> unique = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>(task.getMissingEvidence() == null ? List.of() : task.getMissingEvidence());
        for (EvidenceExtractionResult result : results == null ? List.<EvidenceExtractionResult>of() : results) {
            if (result.getStillMissing() != null) {
                missing.addAll(result.getStillMissing());
            }
            for (ExtractedEvidence evidence : result.getEvidence() == null ? List.<ExtractedEvidence>of() : result.getEvidence()) {
                if (!usable(evidence)) {
                    continue;
                }
                unique.putIfAbsent(evidenceKey(evidence), evidence);
                if (evidence.getMatchedRequirement() != null) {
                    missing.removeIf(value -> evidence.getMatchedRequirement().contains(value) || evidenceText(evidence).contains(value));
                }
                if (evidence.getContent() != null) {
                    missing.removeIf(value -> evidence.getContent().contains(value));
                }
            }
        }
        List<ExtractedEvidence> found = new ArrayList<>(unique.values());
        found.sort(Comparator
                .comparing((ExtractedEvidence evidence) -> evidence.getConfidence() == null ? 0.0d : evidence.getConfidence())
                .reversed()
                .thenComparing(evidence -> evidence.getSourcePath() == null ? "" : evidence.getSourcePath()));
        if (found.size() > MAX_EVIDENCE) {
            found = new ArrayList<>(found.subList(0, MAX_EVIDENCE));
        }
        double confidence = averageConfidence(found);
        return TaskEvidencePack.builder()
                .runId(task.getRunId())
                .taskId(task.getTaskId())
                .ruleId(task.getRuleId())
                .attempt(attempt)
                .foundEvidence(found)
                .missingEvidence(new ArrayList<>(missing))
                .quality(TaskEvidencePack.Quality.builder()
                        .evidenceCount(found.size())
                        .sourceTraceable(found.stream().allMatch(evidence -> evidence.getSourcePath() != null && !evidence.getSourcePath().isBlank()))
                        .confidence(confidence)
                        .repairStatus(found.isEmpty() ? "NO_EVIDENCE_FOUND" : missing.isEmpty() ? "FOUND_COMPLETE_EVIDENCE" : "FOUND_PARTIAL_EVIDENCE")
                        .build())
                .build();
    }

    private boolean usable(ExtractedEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        if (evidence.getConfidence() != null && evidence.getConfidence() < MIN_CONFIDENCE) {
            return false;
        }
        return evidence.getContent() != null && !evidence.getContent().isBlank()
                || evidence.getLayer() != null && !evidence.getLayer().isBlank()
                || evidence.getSourcePath() != null && !evidence.getSourcePath().isBlank();
    }

    private String evidenceKey(ExtractedEvidence evidence) {
        String sourcePath = evidence.getSourcePath() == null ? "" : evidence.getSourcePath();
        if (!sourcePath.isBlank()) {
            return sourcePath;
        }
        return String.join("|",
                nullToEmpty(evidence.getEvidenceType()),
                nullToEmpty(evidence.getLayer()),
                nullToEmpty(evidence.getEntityType()),
                nullToEmpty(evidence.getContent()));
    }

    private String evidenceText(ExtractedEvidence evidence) {
        return String.join(" ",
                nullToEmpty(evidence.getContent()),
                nullToEmpty(evidence.getLayer()),
                nullToEmpty(evidence.getEntityType()),
                nullToEmpty(evidence.getBlockName()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double averageConfidence(List<ExtractedEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        int count = 0;
        for (ExtractedEvidence item : evidence) {
            if (item.getConfidence() != null) {
                total += item.getConfidence();
                count++;
            }
        }
        return count == 0 ? 0.6d : total / count;
    }
}

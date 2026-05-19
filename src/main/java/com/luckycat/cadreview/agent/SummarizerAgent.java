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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class SummarizerAgent {

    private final ChatClient openAiReviewClient;
    private final ChatClient anthropicReviewClient;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;

    public SummarizerAgent(
            @Qualifier("openAiReviewClient") ChatClient openAiReviewClient,
            @Qualifier("anthropicReviewClient") ChatClient anthropicReviewClient,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport) {
        this.openAiReviewClient = openAiReviewClient;
        this.anthropicReviewClient = anthropicReviewClient;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
    }

    public ReviewReport summarize(ReviewSummaryInput input) {
        List<Finding> findings = input.getFindings() == null ? new ArrayList<>() : new ArrayList<>(input.getFindings());
        List<ReviewTask> succeededTasks = nullToList(input.getSucceededTasks());
        List<ReviewTask> failedTasks = nullToList(input.getFailedTasks());
        List<ReviewTask> skippedTasks = nullToList(input.getSkippedTasks());

        List<ConflictGroup> conflicts = detectConflicts(findings);
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

    private void applyVerification(List<Finding> findings, List<ConflictGroup> conflicts) {
        if (!agentProperties.getSummarizer().isVerificationEnabled()) {
            return;
        }
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
                finding.setVerification(Verification.builder()
                        .status("FAILED")
                        .verifiedBy(agentProperties.getSummarizer().getVerifyProvider().name())
                        .comment(ex.getMessage())
                        .build());
                log.warn("Finding verification failed: {}", ex.getMessage());
            }
        }
    }

    private boolean needsVerification(Finding finding) {
        return finding.getRiskLevel() == RiskLevel.HIGH
                && finding.getConfidence() != null
                && finding.getConfidence() < agentProperties.getSummarizer().getVerifyConfidenceThreshold();
    }

    private Verification verify(Finding finding) {
        Provider provider = agentProperties.getSummarizer().getVerifyProvider();
        String userPrompt = toJson(Map.of("finding", finding));
        StructuredOutputSupport.StructuredResult<VerificationOutput> result = structuredOutputSupport.call(
                provider == Provider.ANTHROPIC ? anthropicReviewClient : openAiReviewClient,
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

    private VerificationOutput validateVerificationOutput(VerificationOutput output) {
        if (output == null || output.getVerifiedVerdict() == null) {
            throw new IllegalArgumentException("Verification output must contain verifiedVerdict");
        }
        return output;
    }

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

    private List<String> taskIds(List<ReviewTask> tasks) {
        return tasks.stream()
                .map(ReviewTask::getTaskId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

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

    private boolean entityOverlap(Finding left, Finding right) {
        if (left.getEvidenceEntityIds() == null || right.getEvidenceEntityIds() == null) {
            return false;
        }
        Set<String> leftIds = new LinkedHashSet<>(left.getEvidenceEntityIds());
        leftIds.retainAll(right.getEvidenceEntityIds());
        return !leftIds.isEmpty();
    }

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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize summarizer prompt input", ex);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerificationOutput {
        private Verdict verifiedVerdict;
        private String verifiedReason;
    }
}

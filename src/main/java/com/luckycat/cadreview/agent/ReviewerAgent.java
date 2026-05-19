package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Provider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ReviewerAgent {

    private final ChatClient openAiReviewClient;
    private final ChatClient anthropicReviewClient;
    private final LlmProperties llmProperties;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;

    public ReviewerAgent(
            @Qualifier("openAiReviewClient") ChatClient openAiReviewClient,
            @Qualifier("anthropicReviewClient") ChatClient anthropicReviewClient,
            LlmProperties llmProperties,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport) {
        this.openAiReviewClient = openAiReviewClient;
        this.anthropicReviewClient = anthropicReviewClient;
        this.llmProperties = llmProperties;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
    }

    public List<Finding> review(ReviewTask task, JsonNode relevantIr, List<ReviewRule> rules) {
        if (task == null) {
            throw new IllegalArgumentException("ReviewTask must not be null");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Reviewer must receive at least one rule");
        }
        String userPrompt = toJson(Map.of(
                "task", task,
                "rules", rules,
                "relevantIr", relevantIr
        ));
        StructuredOutputSupport.StructuredResult<ReviewerOutput> result = structuredOutputSupport.call(
                selectClient(llmProperties.getDefaultProvider()),
                promptTemplates.reviewerSystem(),
                userPrompt,
                ReviewerOutput.class,
                agentProperties.getReviewer().getMaxAttempts(),
                "reviewer-" + task.getTaskId(),
                output -> validateOutput(output, task, rules)
        );
        return result.getOutput().getFindings();
    }

    private ReviewerOutput validateOutput(ReviewerOutput output, ReviewTask task, List<ReviewRule> rules) {
        if (output == null) {
            throw new IllegalArgumentException("Reviewer output is null");
        }
        if (output.getFindings() == null) {
            output.setFindings(new ArrayList<>());
        }
        Map<String, ReviewRule> ruleMap = new LinkedHashMap<>();
        for (ReviewRule rule : rules) {
            ruleMap.put(rule.getId(), rule);
        }
        for (Finding finding : output.getFindings()) {
            validateFinding(finding, task, ruleMap);
        }
        return output;
    }

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
            finding.setLayerNames(new ArrayList<>(task.getLayerNames() == null ? List.of() : task.getLayerNames()));
        }
        if (isBlank(finding.getSource())) {
            finding.setSource("REVIEWER_AGENT");
        }
        if (isBlank(finding.getAreaId())) {
            finding.setAreaId(task.getAreaId());
        } else if (!finding.getAreaId().equals(task.getAreaId())) {
            throw new IllegalArgumentException("Finding areaId must match task areaId");
        }
        List<String> taskRuleIds = task.getRuleIds() == null ? List.of() : task.getRuleIds();
        if (isBlank(finding.getRuleId()) && !taskRuleIds.isEmpty()) {
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
            if (isBlank(finding.getRuleId()) || isBlank(finding.getEvidenceText()) || !hasAnchor(finding)) {
                throw new IllegalArgumentException("FAIL finding must contain ruleId, evidenceText and entity/boundingBox anchor");
            }
        }
        if (finding.getVerdict() == Verdict.PENDING_REVIEW && isBlank(finding.getReason())) {
            throw new IllegalArgumentException("PENDING_REVIEW finding must contain reason");
        }
    }

    private boolean hasAnchor(Finding finding) {
        boolean hasEntity = finding.getEvidenceEntityIds() != null && !finding.getEvidenceEntityIds().isEmpty();
        boolean hasBoundingBox = finding.getBoundingBox() != null && finding.getBoundingBox().size() >= 4;
        return hasEntity || hasBoundingBox;
    }

    private ChatClient selectClient(Provider provider) {
        return provider == Provider.ANTHROPIC ? anthropicReviewClient : openAiReviewClient;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize reviewer prompt input", ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewerOutput {
        private List<Finding> findings = new ArrayList<>();
    }
}

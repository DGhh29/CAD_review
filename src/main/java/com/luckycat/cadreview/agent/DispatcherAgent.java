package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.config.LlmProperties;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Provider;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.prompt.PromptTemplates;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DispatcherAgent {

    private final ChatClient openAiReviewClient;
    private final ChatClient anthropicReviewClient;
    private final LlmProperties llmProperties;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;

    public DispatcherAgent(
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

    public List<ReviewTask> dispatch(JsonNode irSummary, List<ReviewRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        String userPrompt = toJson(Map.of(
                "rules", rules,
                "irSummary", irSummary
        ));
        StructuredOutputSupport.StructuredResult<DispatchOutput> result = structuredOutputSupport.call(
                selectClient(llmProperties.getDefaultProvider()),
                promptTemplates.dispatcherSystem(),
                userPrompt,
                DispatchOutput.class,
                agentProperties.getDispatcher().getMaxAttempts(),
                "dispatcher",
                output -> validateOutput(output, rules)
        );
        return result.getOutput().getTasks();
    }

    private DispatchOutput validateOutput(DispatchOutput output, List<ReviewRule> rules) {
        if (output == null) {
            throw new IllegalArgumentException("Dispatcher output is null");
        }
        if (output.getTasks() == null) {
            output.setTasks(new ArrayList<>());
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
        return output;
    }

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
            task.setCheckItem(ruleMap.get(task.getRuleIds().get(0)).getTitle());
        }
        if (task.getEntityIds() == null) {
            task.setEntityIds(new ArrayList<>());
        }
        if (task.getLayerNames() == null) {
            task.setLayerNames(new ArrayList<>());
        }
        if (isBlank(task.getAreaId())) {
            task.setAreaId("UNKNOWN");
        }
        if (task.getPriority() == null) {
            task.setPriority(RiskLevel.MEDIUM);
        }
    }

    static Comparator<ReviewTask> taskComparator() {
        return Comparator
                .comparingInt((ReviewTask task) -> priorityRank(task.getPriority())).reversed()
                .thenComparing(task -> first(task.getRuleIds()))
                .thenComparing(task -> String.join(",", task.getLayerNames() == null ? List.of() : task.getLayerNames()))
                .thenComparing(ReviewTask::getTaskId, Comparator.nullsLast(String::compareTo));
    }

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

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private ChatClient selectClient(Provider provider) {
        return provider == Provider.ANTHROPIC ? anthropicReviewClient : openAiReviewClient;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize dispatcher prompt input", ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatchOutput {
        private List<ReviewTask> tasks = new ArrayList<>();
    }
}

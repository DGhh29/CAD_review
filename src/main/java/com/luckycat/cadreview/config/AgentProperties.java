package com.luckycat.cadreview.config;

import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.enums.Provider;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "cad-review.agent")
public class AgentProperties {

    private int maxReviewTasks = 10;
    private int maxReviewEntities = 800;
    private int totalTimeoutSeconds = 120;
    private double unanchoredPendingThreshold = 0.5d;

    @Valid
    private Dispatcher dispatcher = new Dispatcher();

    @Valid
    private Reviewer reviewer = new Reviewer();

    @Valid
    private Summarizer summarizer = new Summarizer();

    @Valid
    private List<ReviewRule> rules = new ArrayList<>();

    @PostConstruct
    public void validateRules() {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        Map<String, Long> duplicates = rules.stream()
                .filter(rule -> rule != null && rule.getId() != null && rule.getVersion() != null)
                .collect(Collectors.groupingBy(rule -> rule.getId() + "@" + rule.getVersion(), LinkedHashMap::new, Collectors.counting()));
        List<String> repeated = duplicates.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!repeated.isEmpty()) {
            throw new IllegalArgumentException("Duplicate review rules found: " + repeated);
        }

        Map<String, Long> enabledRuleIds = rules.stream()
                .filter(rule -> rule != null && rule.isEnabled())
                .filter(rule -> rule.getId() != null)
                .collect(Collectors.groupingBy(ReviewRule::getId, LinkedHashMap::new, Collectors.counting()));
        List<String> repeatedIds = enabledRuleIds.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!repeatedIds.isEmpty()) {
            throw new IllegalArgumentException("Enabled review rule ids must be unique: " + repeatedIds);
        }

    }

    public List<ReviewRule> selectRules(String ruleSet) {
        Set<String> requested = parseRuleSet(ruleSet);
        List<ReviewRule> enabledRules = rules.stream()
                .filter(rule -> rule != null && rule.isEnabled())
                .filter(rule -> rule.getId() != null)
                .sorted(Comparator.comparing(ReviewRule::getId))
                .toList();
        if (requested.isEmpty()) {
            return enabledRules;
        }
        List<ReviewRule> selected = enabledRules.stream()
                .filter(rule -> requested.contains(rule.getId()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No matching review rules found for ruleSet: " + ruleSet);
        }
        return selected;
    }

    public Map<String, ReviewRule> ruleMap(List<ReviewRule> selectedRules) {
        Map<String, ReviewRule> map = new LinkedHashMap<>();
        for (ReviewRule rule : selectedRules) {
            if (map.containsKey(rule.getId())) {
                throw new IllegalArgumentException("Duplicate rule id in selected rules: " + rule.getId());
            }
            map.put(rule.getId(), rule);
        }
        return map;
    }

    private Set<String> parseRuleSet(String ruleSet) {
        if (ruleSet == null || ruleSet.isBlank() || "all".equalsIgnoreCase(ruleSet.trim())) {
            return Set.of();
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String token : ruleSet.split("[,;\\s]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                requested.add(trimmed);
            }
        }
        return requested;
    }

    @Data
    public static class Dispatcher {
        private int timeoutSeconds = 30;
        private int maxAttempts = 1;
    }

    @Data
    public static class Reviewer {
        private int corePoolSize = 3;
        private int parallelMax = 5;
        private int queueCapacity = 10;
        private int timeoutSeconds = 30;
        private int maxAttempts = 1;
    }

    @Data
    public static class Summarizer {
        private int reserveSeconds = 30;
        private boolean verificationEnabled = false;
        private Provider verifyProvider = Provider.ANTHROPIC;
        private double verifyConfidenceThreshold = 0.7d;
        private int maxAttempts = 1;
        private int timeoutSeconds = 30;
    }
}

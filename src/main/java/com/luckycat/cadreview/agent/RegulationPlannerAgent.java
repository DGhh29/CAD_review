package com.luckycat.cadreview.agent;

import com.luckycat.cadreview.dto.ReviewRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 法规/规则规划器。
 *
 * <p>v1 先做确定性摘要，不额外调用 LLM：把国家法规、地方规则、自定义规则统一压成
 * Dispatcher 需要的 RuleDigest。后续如果要接 RAG 或法规原文分块摘要，可以在这里替换实现。
 */
@Component
public class RegulationPlannerAgent {

    public List<RuleDigest> digest(List<ReviewRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
                .map(this::digestOne)
                .toList();
    }

    private RuleDigest digestOne(ReviewRule rule) {
        return RuleDigest.builder()
                .ruleId(rule.getId())
                .clauseId(rule.getClauseId())
                .title(rule.getTitle())
                .scope(limit(rule.getScope(), 300))
                .version(rule.getVersion())
                .outputRequirement(limit(rule.getPromptFragment(), 500))
                .priorityHint(priorityHint(rule))
                .build();
    }

    private String priorityHint(ReviewRule rule) {
        String text = (rule.getTitle() + " " + rule.getScope() + " " + rule.getPromptFragment())
                .toLowerCase(Locale.ROOT);
        if (text.contains("消防") || text.contains("防火") || text.contains("疏散")
                || text.contains("强制") || text.contains("必须")) {
            return "HIGH";
        }
        if (text.contains("停车") || text.contains("充电") || text.contains("退距")
                || text.contains("红线") || text.contains("指标")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...[truncated]";
    }
}

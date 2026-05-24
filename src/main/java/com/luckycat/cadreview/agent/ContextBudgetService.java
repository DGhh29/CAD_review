package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.luckycat.cadreview.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有 LLM 调用前的上下文预算器。
 *
 * <p>它不依赖具体模型 tokenizer，采用保守的字符/字节估算。目标不是精确计费，
 * 而是在进入模型前主动把超长 JSON 压到可控范围，避免由上游报错驱动流程失败。
 */
@Component
@RequiredArgsConstructor
public class ContextBudgetService {

    private static final List<String> HIGH_VALUE_FIELDS = List.of(
            "drawing", "quality", "drawing_brief", "summary", "statistics", "semantic",
            "rules", "task", "findings", "conflicts", "coverage", "evidence_groups",
            "clean_texts", "clean_dimensions", "indicator_rows", "selectedTexts", "selectedDimensions"
    );

    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public ContextEnvelope wrap(AgentRole role, String stage, String taskId, JsonNode context) {
        int maxChars = maxChars(role);
        JsonNode original = context == null ? objectMapper.createObjectNode() : context;
        Size originalSize = sizeOf(original);
        Map<String, Integer> originalCounts = collectArrayCounts(original);
        JsonNode reduced = original.deepCopy();
        ContextOverflowPolicy policy = ContextOverflowPolicy.NONE;
        boolean overflow = originalSize.chars() > maxChars;

        if (overflow) {
            policy = ContextOverflowPolicy.SHRINK_SUMMARY;
            reduced = shrink(reduced, agentProperties.getContextBudget().getFirstPassArrayItems());
        }
        if (sizeOf(reduced).chars() > maxChars) {
            policy = ContextOverflowPolicy.SELECT_TOP_K_EVIDENCE;
            reduced = shrink(reduced, agentProperties.getContextBudget().getSecondPassArrayItems());
        }
        if (sizeOf(reduced).chars() > maxChars) {
            policy = ContextOverflowPolicy.SPLIT_AND_REDUCE;
            reduced = shrink(reduced, agentProperties.getContextBudget().getFinalPassArrayItems());
        }
        if (sizeOf(reduced).chars() > maxChars) {
            policy = role == AgentRole.REVIEWER ? ContextOverflowPolicy.PENDING_REVIEW : ContextOverflowPolicy.UPGRADE_MODEL;
            reduced = keepHighValueFields(reduced);
        }

        Size finalSize = sizeOf(reduced);
        Map<String, Integer> retainedCounts = collectArrayCounts(reduced);
        Map<String, Integer> droppedCounts = droppedCounts(originalCounts, retainedCounts);
        ContextBudget budget = ContextBudget.builder()
                .role(role)
                .stage(stage)
                .taskId(taskId)
                .maxChars(maxChars)
                .originalChars(originalSize.chars())
                .finalChars(finalSize.chars())
                .originalJsonBytes(originalSize.bytes())
                .finalJsonBytes(finalSize.bytes())
                .estimatedOriginalTokens(estimateTokens(originalSize.chars()))
                .estimatedFinalTokens(estimateTokens(finalSize.chars()))
                .overflow(overflow || !droppedCounts.isEmpty() || finalSize.chars() > maxChars)
                .overflowPolicy(policy)
                .retainedCounts(retainedCounts)
                .droppedCounts(droppedCounts)
                .build();

        return ContextEnvelope.builder()
                .stage(stage)
                .taskId(taskId)
                .budget(budget)
                .context(reduced)
                .quality(readQuality(reduced))
                .omitted(omitted(droppedCounts, finalSize.chars() > maxChars))
                .build();
    }

    public String toPromptJson(ContextEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize context envelope", ex);
        }
    }

    private int maxChars(AgentRole role) {
        AgentProperties.ContextBudgetConfig config = agentProperties.getContextBudget();
        if (role == AgentRole.DISPATCHER || role == AgentRole.REGULATION_PLANNER) {
            return config.getDispatcherMaxChars();
        }
        if (role == AgentRole.PRE_CLEANER) {
            return config.getPreCleanerMaxChars();
        }
        if (role == AgentRole.REVIEWER) {
            return config.getReviewerMaxChars();
        }
        return config.getSummarizerMaxChars();
    }

    private JsonNode shrink(JsonNode node, int maxArrayItems) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            int index = 0;
            for (JsonNode item : node) {
                if (index++ >= maxArrayItems) {
                    break;
                }
                result.add(shrink(item, maxArrayItems));
            }
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), shrink(field.getValue(), maxArrayItems));
            }
            return result;
        }
        if (node.isTextual()) {
            String value = node.asText();
            int maxTextChars = agentProperties.getContextBudget().getMaxTextChars();
            if (value.length() > maxTextChars) {
                return TextNode.valueOf(value.substring(0, maxTextChars) + "...[truncated]");
            }
        }
        return node.deepCopy();
    }

    private JsonNode keepHighValueFields(JsonNode node) {
        if (!node.isObject()) {
            return shrink(node, agentProperties.getContextBudget().getFinalPassArrayItems());
        }
        ObjectNode result = objectMapper.createObjectNode();
        for (String field : HIGH_VALUE_FIELDS) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                result.set(field, shrink(value, agentProperties.getContextBudget().getFinalPassArrayItems()));
            }
        }
        if (result.size() == 0) {
            return shrink(node, agentProperties.getContextBudget().getFinalPassArrayItems());
        }
        return result;
    }

    private JsonNode readQuality(JsonNode node) {
        JsonNode quality = node.path("quality");
        return quality.isMissingNode() ? objectMapper.createObjectNode() : quality.deepCopy();
    }

    private Size sizeOf(JsonNode node) {
        String json = node == null ? "" : node.toString();
        return new Size(json.length(), json.getBytes(StandardCharsets.UTF_8).length);
    }

    private int estimateTokens(int chars) {
        return Math.max(1, (int) Math.ceil(chars / 3.5d));
    }

    private Map<String, Integer> collectArrayCounts(JsonNode node) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        collectArrayCounts("", node, counts);
        return counts;
    }

    private void collectArrayCounts(String path, JsonNode node, Map<String, Integer> counts) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            counts.put(path.isBlank() ? "$" : path, node.size());
            int index = 0;
            for (JsonNode child : node) {
                collectArrayCounts(path + "[]", child, counts);
                if (++index >= 3) {
                    break;
                }
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path.isBlank() ? field.getKey() : path + "." + field.getKey();
                collectArrayCounts(childPath, field.getValue(), counts);
            }
        }
    }

    private Map<String, Integer> droppedCounts(Map<String, Integer> originalCounts, Map<String, Integer> retainedCounts) {
        Map<String, Integer> dropped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : originalCounts.entrySet()) {
            int retained = retainedCounts.getOrDefault(entry.getKey(), 0);
            int diff = entry.getValue() - retained;
            if (diff > 0) {
                dropped.put(entry.getKey(), diff);
            }
        }
        return dropped;
    }

    private List<String> omitted(Map<String, Integer> droppedCounts, boolean stillOverflow) {
        List<String> omitted = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> entry : droppedCounts.entrySet()) {
            omitted.add(entry.getKey() + " dropped " + entry.getValue());
        }
        if (stillOverflow) {
            omitted.add("context still exceeds maxChars after local shrink; reviewer should output PENDING_REVIEW when evidence is insufficient");
        }
        return omitted;
    }

    private record Size(int chars, int bytes) {
    }
}

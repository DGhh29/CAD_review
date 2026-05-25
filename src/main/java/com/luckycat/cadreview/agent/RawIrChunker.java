package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按 EvidenceSearchTask 的线索从 raw_ir 中切出小批候选证据。
 *
 * <p>这里避免按 JSON 行号硬切，而是按语义来源切：文字、尺寸、实体、块、指标表。
 */
@Component
@RequiredArgsConstructor
public class RawIrChunker {

    private static final int DEFAULT_MAX_CHUNKS = 40;
    private static final int MAX_ITEMS_PER_CHUNK = 40;

    private final ObjectMapper objectMapper;

    public List<EvidenceChunk> chunk(JsonNode rawIr, EvidenceSearchTask task) {
        if (rawIr == null || rawIr.isMissingNode() || rawIr.isNull()) {
            return List.of();
        }
        int maxChunks = task.getMaxChunks() > 0 ? task.getMaxChunks() : DEFAULT_MAX_CHUNKS;
        List<EvidenceChunk> chunks = new ArrayList<>();
        addArrayChunks(chunks, rawIr.path("texts"), "texts", "raw_ir.texts", task, maxChunks);
        addArrayChunks(chunks, rawIr.path("dimensions"), "dimensions", "raw_ir.dimensions", task, maxChunks);
        addArrayChunks(chunks, rawIr.path("entities"), "entities", "raw_ir.entities", task, maxChunks);
        addArrayChunks(chunks, rawIr.path("blocks"), "blocks", "raw_ir.blocks", task, maxChunks);
        addArrayChunks(chunks, rawIr.path("indicator_rows"), "indicator_rows", "raw_ir.indicator_rows", task, maxChunks);
        return chunks.size() <= maxChunks ? chunks : new ArrayList<>(chunks.subList(0, maxChunks));
    }

    private void addArrayChunks(
            List<EvidenceChunk> chunks,
            JsonNode array,
            String chunkType,
            String source,
            EvidenceSearchTask task,
            int maxChunks) {
        if (!array.isArray() || chunks.size() >= maxChunks) {
            return;
        }
        List<JsonNode> buffer = new ArrayList<>();
        int matched = 0;
        int index = 0;
        for (JsonNode item : array) {
            if (matches(item, task, chunkType)) {
                buffer.add(withSourcePath(item, source + "[" + index + "]"));
                matched++;
                if (buffer.size() >= MAX_ITEMS_PER_CHUNK) {
                    chunks.add(buildChunk(chunkType, source, task, chunks.size() + 1, buffer, matched));
                    buffer = new ArrayList<>();
                    if (chunks.size() >= maxChunks) {
                        return;
                    }
                }
            }
            index++;
        }
        if (!buffer.isEmpty() && chunks.size() < maxChunks) {
            chunks.add(buildChunk(chunkType, source, task, chunks.size() + 1, buffer, matched));
        }
    }

    private EvidenceChunk buildChunk(
            String chunkType,
            String source,
            EvidenceSearchTask task,
            int sequence,
            List<JsonNode> items,
            int matchedCount) {
        String ruleId = task.getRuleId() == null || task.getRuleId().isBlank() ? "UNKNOWN_RULE" : task.getRuleId();
        return EvidenceChunk.builder()
                .chunkId(chunkType + "-" + ruleId + "-" + String.format("%03d", sequence))
                .chunkType(chunkType)
                .source(source)
                .reason("命中补证线索，累计候选 " + matchedCount + " 条")
                .items(new ArrayList<>(items))
                .build();
    }

    private JsonNode withSourcePath(JsonNode item, String sourcePath) {
        ObjectNode copy = item != null && item.isObject()
                ? ((ObjectNode) item).deepCopy()
                : objectMapper.createObjectNode().set("value", item == null ? objectMapper.nullNode() : item.deepCopy());
        copy.put("sourcePath", sourcePath);
        copy.put("source_path", sourcePath);
        return copy;
    }

    private boolean matches(JsonNode item, EvidenceSearchTask task, String chunkType) {
        if (item == null || item.isNull()) {
            return false;
        }
        Set<String> needles = normalizedNeedles(task);
        String searchable = searchableText(item);
        if (containsAny(searchable, needles)) {
            return true;
        }
        if ("dimensions".equals(chunkType) && textSuggestsDimensionNeed(task)) {
            return true;
        }
        if ("indicator_rows".equals(chunkType) && textSuggestsTableNeed(task)) {
            return true;
        }
        if ("entities".equals(chunkType) || "blocks".equals(chunkType)) {
            return matchesEntityType(item, task);
        }
        return false;
    }

    private Set<String> normalizedNeedles(EvidenceSearchTask task) {
        Set<String> values = new LinkedHashSet<>();
        addAll(values, task.getKeywords());
        addAll(values, task.getLayerHints());
        addAll(values, task.getMissingEvidence());
        addAll(values, task.getEntityTypeHints());
        values.removeIf(String::isBlank);
        return values;
    }

    private void addAll(Set<String> values, List<String> source) {
        if (source == null) {
            return;
        }
        for (String item : source) {
            if (item != null && !item.isBlank()) {
                values.add(item.toLowerCase(Locale.ROOT));
            }
        }
    }

    private boolean containsAny(String searchable, Set<String> needles) {
        if (searchable.isBlank() || needles.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (searchable.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String searchableText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        appendField(text, item, "text");
        appendField(text, item, "content");
        appendField(text, item, "layer");
        appendField(text, item, "type");
        appendField(text, item, "entityType");
        appendField(text, item, "entity_type");
        appendField(text, item, "blockName");
        appendField(text, item, "block_name");
        appendField(text, item, "name");
        appendField(text, item, "label");
        appendField(text, item, "value");
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private void appendField(StringBuilder text, JsonNode item, String fieldName) {
        JsonNode value = item.path(fieldName);
        if (!value.isMissingNode() && !value.isNull()) {
            text.append(' ').append(value.asText());
        }
    }

    private boolean matchesEntityType(JsonNode item, EvidenceSearchTask task) {
        if (task.getEntityTypeHints() == null || task.getEntityTypeHints().isEmpty()) {
            return false;
        }
        String type = firstText(item, "type", "entityType", "entity_type").toLowerCase(Locale.ROOT);
        String blockName = firstText(item, "blockName", "block_name", "name").toLowerCase(Locale.ROOT);
        for (String hint : task.getEntityTypeHints()) {
            String normalized = hint == null ? "" : hint.toLowerCase(Locale.ROOT);
            if (!normalized.isBlank() && (type.contains(normalized) || blockName.contains(normalized))) {
                return true;
            }
        }
        return false;
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode value = item.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private boolean textSuggestsDimensionNeed(EvidenceSearchTask task) {
        String text = taskText(task);
        return text.contains("宽") || text.contains("净宽") || text.contains("半径")
                || text.contains("r=") || text.contains("dimension") || text.contains("尺寸");
    }

    private boolean textSuggestsTableNeed(EvidenceSearchTask task) {
        String text = taskText(task);
        return text.contains("停车") || text.contains("车位") || text.contains("建筑面积")
                || text.contains("指标") || text.contains("面积");
    }

    private String taskText(EvidenceSearchTask task) {
        return String.join(" ",
                task.getCategory() == null ? "" : task.getCategory(),
                task.getRuleId() == null ? "" : task.getRuleId(),
                String.join(" ", task.getMissingEvidence() == null ? List.of() : task.getMissingEvidence()),
                String.join(" ", task.getKeywords() == null ? List.of() : task.getKeywords()))
                .toLowerCase(Locale.ROOT);
    }
}

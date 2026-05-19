package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.ReviewTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class IrViewService {

    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    public JsonNode buildSummary(JsonNode drawingIr) {
        ObjectNode summary = objectMapper.createObjectNode();
        copyIfPresent(summary, drawingIr, "schema_version");
        copyIfPresent(summary, drawingIr, "success");
        copyIfPresent(summary, drawingIr, "source");
        copyIfPresent(summary, drawingIr, "metadata");
        copyIfPresent(summary, drawingIr, "summary");
        copyIfPresent(summary, drawingIr, "statistics");
        copyIfPresent(summary, drawingIr, "semantic");
        copyIfPresent(summary, drawingIr, "warnings");
        copyIfPresent(summary, drawingIr, "audit_pack");

        summary.set("layers", limitArray(drawingIr.path("layers"), 30));
        summary.set("blocks", limitArray(drawingIr.path("blocks"), 20));
        summary.set("texts", limitArray(drawingIr.path("texts"), 40));
        summary.set("dimensions", limitArray(drawingIr.path("dimensions"), 40));
        summary.set("entitySamples", sampleEntities(drawingIr.path("entities"), agentProperties.getMaxReviewEntities()));
        summary.put("entitySampleLimit", agentProperties.getMaxReviewEntities());
        return summary;
    }

    public JsonNode slice(JsonNode drawingIr, List<String> entityIds, List<String> layerNames) {
        ObjectNode slice = objectMapper.createObjectNode();
        copyIfPresent(slice, drawingIr, "schema_version");
        copyIfPresent(slice, drawingIr, "source");
        copyIfPresent(slice, drawingIr, "summary");
        copyIfPresent(slice, drawingIr, "statistics");
        copyIfPresent(slice, drawingIr, "semantic");
        copyIfPresent(slice, drawingIr, "warnings");

        Set<String> normalizedLayerNames = normalize(layerNames);
        Set<String> normalizedEntityIds = normalize(entityIds);
        ArrayNode entities = objectMapper.createArrayNode();
        ArrayNode texts = objectMapper.createArrayNode();
        ArrayNode dimensions = objectMapper.createArrayNode();

        int maxEntities = agentProperties.getMaxReviewEntities();
        for (JsonNode entity : iterable(drawingIr.path("entities"))) {
            if (entities.size() >= maxEntities) {
                break;
            }
            if (matchesEntity(entity, normalizedEntityIds, normalizedLayerNames)) {
                entities.add(entity.deepCopy());
            }
        }
        for (JsonNode text : iterable(drawingIr.path("texts"))) {
            if (matchesLayer(text, normalizedLayerNames)) {
                texts.add(text.deepCopy());
            }
        }
        for (JsonNode dimension : iterable(drawingIr.path("dimensions"))) {
            if (matchesLayer(dimension, normalizedLayerNames)) {
                dimensions.add(dimension.deepCopy());
            }
        }

        slice.set("selectedEntities", entities);
        slice.set("selectedTexts", texts);
        slice.set("selectedDimensions", dimensions);
        slice.set("selectedLayers", limitByLayer(drawingIr.path("layers"), normalizedLayerNames));
        slice.put("selectionHint", buildSelectionHint(entityIds, layerNames, entities.size()));
        return slice;
    }

    private ArrayNode sampleEntities(JsonNode entitiesNode, int limit) {
        ArrayNode samples = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode entity : iterable(entitiesNode)) {
            if (count >= Math.min(limit, 80)) {
                break;
            }
            samples.add(entity.deepCopy());
            count++;
        }
        return samples;
    }

    private ArrayNode limitArray(JsonNode node, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode item : iterable(node)) {
            if (count >= limit) {
                break;
            }
            result.add(item.deepCopy());
            count++;
        }
        return result;
    }

    private ArrayNode limitByLayer(JsonNode layersNode, Set<String> normalizedLayerNames) {
        ArrayNode result = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode layer : iterable(layersNode)) {
            if (count >= 40) {
                break;
            }
            if (normalizedLayerNames.isEmpty() || normalizedLayerNames.contains(normalize(layer.path("name").asText()))) {
                result.add(layer.deepCopy());
                count++;
            }
        }
        return result;
    }

    private boolean matchesEntity(JsonNode entity, Set<String> normalizedEntityIds, Set<String> normalizedLayerNames) {
        if (!normalizedEntityIds.isEmpty()) {
            String handle = normalize(entity.path("handle").asText(null));
            String index = normalize(entity.path("index").asText(null));
            int parsedIndex = index != null ? parseIntSafe(index) : -1;
            String generated = parsedIndex >= 0 ? "entity-" + String.format(Locale.ROOT, "%03d", parsedIndex) : null;
            if (handle != null && normalizedEntityIds.contains(handle)) {
                return true;
            }
            if (index != null && normalizedEntityIds.contains(index)) {
                return true;
            }
            if (generated != null && normalizedEntityIds.contains(generated)) {
                return true;
            }
            return !normalizedLayerNames.isEmpty() && matchesLayer(entity, normalizedLayerNames);
        }
        return matchesLayer(entity, normalizedLayerNames);
    }

    private boolean matchesLayer(JsonNode node, Set<String> normalizedLayerNames) {
        if (normalizedLayerNames.isEmpty()) {
            return true;
        }
        String layer = normalize(node.path("layer").asText(null));
        if (layer != null && normalizedLayerNames.contains(layer)) {
            return true;
        }
        String name = normalize(node.path("name").asText(null));
        return name != null && normalizedLayerNames.contains(name);
    }

    private void copyIfPresent(ObjectNode target, JsonNode source, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && !value.isNull()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    private Set<String> normalize(List<String> values) {
        Set<String> result = new HashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return -1;
        }
    }

    private String buildSelectionHint(List<String> entityIds, List<String> layerNames, int entityCount) {
        return "entityIds=" + (entityIds == null ? 0 : entityIds.size())
                + ", layerNames=" + (layerNames == null ? 0 : layerNames.size())
                + ", selectedEntities=" + entityCount;
    }
}

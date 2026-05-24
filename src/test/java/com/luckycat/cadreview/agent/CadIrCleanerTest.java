package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CadIrCleanerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldKeepProfessionalAndUnclassifiedEvidence() throws Exception {
        CadIrCleaner cleaner = new CadIrCleaner(objectMapper);
        JsonNode ir = objectMapper.readTree("""
                {
                  "schema_version": "cad-drawing-parser.v1",
                  "success": true,
                  "summary": {
                    "entity_count": 7,
                    "bbox": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 100}
                  },
                  "layers": [
                    {"name": "给排水", "entity_count": 1, "is_off": false, "is_frozen": false},
                    {"name": "HVAC", "entity_count": 1, "is_off": false, "is_frozen": false},
                    {"name": "电气", "entity_count": 1, "is_off": false, "is_frozen": false},
                    {"name": "结构柱", "entity_count": 1, "is_off": false, "is_frozen": false},
                    {"name": "UNKNOWN", "entity_count": 1, "is_off": false, "is_frozen": false}
                  ],
                  "blocks": [
                    {"name": "RAW_BLOCK", "entity_count": 1, "insert_count": 1}
                  ],
                  "texts": [
                    {"layer": "给排水", "text": "给水管 DN100", "point": [10, 10, 0]},
                    {"layer": "HVAC", "text": "风管 800x400", "point": [20, 10, 0]},
                    {"layer": "电气", "text": "配电箱", "point": [30, 10, 0]},
                    {"layer": "幕墙", "text": "幕墙龙骨", "point": [40, 10, 0]},
                    {"layer": "园林", "text": "乔木种植", "point": [50, 10, 0]}
                  ],
                  "dimensions": [
                    {"layer": "UNKNOWN", "measurement": 1200.0, "text": "", "point": [60, 10, 0]}
                  ],
                  "entities": [
                    {"index": 1, "handle": "S1", "layer": "结构柱", "type": "INSERT", "semantic": "structure", "insert": [10, 20, 0]},
                    {"index": 2, "handle": "U1", "layer": "UNKNOWN", "type": "LINE", "start": [10, 30, 0], "end": [20, 30, 0]}
                  ]
                }
                """);

        JsonNode context = cleaner.buildReviewContext(ir);

        assertThat(context.path("evidence_groups").path("plumbing").path("texts").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("hvac").path("texts").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("electrical").path("texts").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("curtain_wall").path("texts").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("landscape").path("texts").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("structure").path("entities").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("unclassified").path("entities").size()).isEqualTo(1);
        assertThat(context.path("evidence_groups").path("unclassified").path("blocks").size()).isEqualTo(1);
        assertThat(context.path("detected_disciplines").toString()).contains("plumbing", "hvac", "electrical", "structure");
        assertThat(context.path("review_readiness").path("geometry_engine_required").asBoolean()).isTrue();
        assertThat(context.path("review_readiness").path("geometry_engine_required_for").toString()).contains("clear_distance", "collision");
    }

    @Test
    void shouldKeepPriorityFireFacilityEvidenceWhenGenericFireEvidenceExceedsLimits() {
        CadIrCleaner cleaner = new CadIrCleaner(objectMapper);
        ObjectNode ir = objectMapper.createObjectNode();
        ir.put("schema_version", "cad-drawing-parser.v1");
        ir.put("success", true);
        ObjectNode summary = ir.putObject("summary");
        summary.put("entity_count", 201);
        ObjectNode bbox = summary.putObject("bbox");
        bbox.put("min_x", 0);
        bbox.put("min_y", 0);
        bbox.put("max_x", 1000);
        bbox.put("max_y", 1000);

        ArrayNode layers = ir.putArray("layers");
        addLayer(layers, "DOOR_FIRE", 200);
        addLayer(layers, "DOOR_FIRE_TEXT", 200);
        addLayer(layers, "EQUIP_消防", 1);
        addLayer(layers, "PUB_TEXT", 1);
        ir.putArray("blocks");
        ir.putArray("dimensions");

        ArrayNode texts = ir.putArray("texts");
        for (int i = 0; i < 200; i++) {
            ObjectNode text = texts.addObject();
            text.put("layer", "DOOR_FIRE_TEXT");
            text.put("text", "fire door note " + i);
            ArrayNode point = text.putArray("point");
            point.add(i);
            point.add(10);
            point.add(0);
        }
        ObjectNode hydrantText = texts.addObject();
        hydrantText.put("layer", "PUB_TEXT");
        hydrantText.put("text", "表示消火栓，宽");
        ArrayNode hydrantPoint = hydrantText.putArray("point");
        hydrantPoint.add(900);
        hydrantPoint.add(10);
        hydrantPoint.add(0);

        ArrayNode entities = ir.putArray("entities");
        for (int i = 0; i < 200; i++) {
            addEntity(entities, "D" + i, "DOOR_FIRE", "LINE", "fire", i, 20);
        }
        ObjectNode equip = addEntity(entities, "FX1", "EQUIP_消防", "INSERT", "fire", 900, 20);
        equip.put("block", "$ATTACHMENT$00000214");

        JsonNode context = cleaner.buildReviewContext(ir);

        assertThat(context.path("clean_entity_samples").toString()).contains("FX1");
        assertThat(context.path("evidence_groups").path("fire").path("entities").toString()).contains("FX1");
        assertThat(context.path("clean_texts").toString()).contains("消火栓");
        assertThat(context.path("evidence_groups").path("fire").path("texts").toString()).contains("消火栓");
        assertThat(context.path("quality").path("kept_counts").path("priority_entities").asInt()).isEqualTo(1);
        assertThat(context.path("quality").path("kept_counts").path("priority_texts").asInt()).isEqualTo(1);
    }

    @Test
    void shouldRebuildIndicatorRowsAndKeepNumericValuesBesideLabels() {
        CadIrCleaner cleaner = new CadIrCleaner(objectMapper);
        ObjectNode ir = objectMapper.createObjectNode();
        ir.put("schema_version", "cad-drawing-parser.v1");
        ir.put("success", true);
        ObjectNode summary = ir.putObject("summary");
        summary.put("entity_count", 0);
        ObjectNode bbox = summary.putObject("bbox");
        bbox.put("min_x", 0);
        bbox.put("min_y", 0);
        bbox.put("max_x", 10000);
        bbox.put("max_y", 10000);
        ArrayNode layers = ir.putArray("layers");
        addLayer(layers, "PUB_TEXT", 8);
        ir.putArray("blocks");
        ir.putArray("dimensions");
        ir.putArray("entities");

        ArrayNode texts = ir.putArray("texts");
        addText(texts, "PUB_TEXT", "本层建筑面积：", 1000, 5000);
        addText(texts, "PUB_TEXT", "8053.91m", 4200, 5000);
        addText(texts, "PUB_TEXT", "本层机动车停车位：", 1000, 4000);
        addText(texts, "PUB_TEXT", "62", 5200, 4000);
        addText(texts, "PUB_TEXT", "个", 6100, 4000);
        addText(texts, "PUB_TEXT", "普通机动车停车位：", 1000, 3000);
        addText(texts, "PUB_TEXT", "60", 5200, 3000);

        JsonNode context = cleaner.buildReviewContext(ir);

        assertThat(context.path("indicator_rows").toString()).contains("本层建筑面积", "8053.91m", "本层机动车停车位", "62", "普通机动车停车位", "60");
        assertThat(context.path("evidence_groups").path("parking").path("texts").toString()).contains("62", "60", "8053.91m");
        assertThat(context.path("clean_texts").toString()).contains("62", "60");
        assertThat(context.path("quality").path("kept_counts").path("indicator_rows").asInt()).isEqualTo(3);
        assertThat(context.path("quality").path("kept_counts").path("indicator_value_texts").asInt()).isGreaterThanOrEqualTo(3);
    }

    private void addLayer(ArrayNode layers, String name, int entityCount) {
        ObjectNode layer = layers.addObject();
        layer.put("name", name);
        layer.put("entity_count", entityCount);
        layer.put("is_off", false);
        layer.put("is_frozen", false);
    }

    private ObjectNode addEntity(ArrayNode entities, String handle, String layerName, String type, String semantic, int x, int y) {
        ObjectNode entity = entities.addObject();
        entity.put("handle", handle);
        entity.put("layer", layerName);
        entity.put("type", type);
        entity.put("semantic", semantic);
        ObjectNode entityBbox = entity.putObject("bbox");
        entityBbox.put("min_x", x);
        entityBbox.put("min_y", y);
        entityBbox.put("max_x", x + 1);
        entityBbox.put("max_y", y + 1);
        entityBbox.put("width", 1);
        entityBbox.put("height", 1);
        return entity;
    }

    private void addText(ArrayNode texts, String layerName, String content, int x, int y) {
        ObjectNode text = texts.addObject();
        text.put("layer", layerName);
        text.put("text", content);
        ArrayNode point = text.putArray("point");
        point.add(x);
        point.add(y);
        point.add(0);
    }
}

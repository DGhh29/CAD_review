package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IrViewServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildSummaryWithoutFullEntityArray() throws Exception {
        IrViewService service = new IrViewService(objectMapper, buildProperties());
        JsonNode ir = sampleIr();

        JsonNode summary = service.buildSummary(ir);

        assertThat(summary.has("entities")).isFalse();
        assertThat(summary.path("entitySamples").size()).isEqualTo(2);
        assertThat(summary.path("layers").size()).isEqualTo(2);
    }

    @Test
    void shouldSliceByEntityIdsAndLayerNames() throws Exception {
        IrViewService service = new IrViewService(objectMapper, buildProperties());
        JsonNode ir = sampleIr();

        JsonNode byEntity = service.slice(ir, List.of("A1"), List.of());
        JsonNode byLayer = service.slice(ir, List.of(), List.of("DOOR"));

        assertThat(byEntity.path("selectedEntities").size()).isEqualTo(1);
        assertThat(byEntity.path("selectedEntities").get(0).path("handle").asText()).isEqualTo("A1");
        assertThat(byLayer.path("selectedEntities").size()).isEqualTo(1);
        assertThat(byLayer.path("selectedEntities").get(0).path("handle").asText()).isEqualTo("B2");
        assertThat(byLayer.path("selectedDimensions").size()).isEqualTo(1);
    }

    private AgentProperties buildProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxReviewEntities(10);
        return properties;
    }

    private JsonNode sampleIr() throws Exception {
        return objectMapper.readTree("""
                {
                  "schema_version": "cad-drawing-parser.v1",
                  "summary": {"entity_count": 2},
                  "layers": [
                    {"name": "FIRE", "entity_count": 1},
                    {"name": "DOOR", "entity_count": 1}
                  ],
                  "entities": [
                    {"index": 1, "handle": "A1", "layer": "FIRE", "type": "LWPOLYLINE"},
                    {"index": 2, "handle": "B2", "layer": "DOOR", "type": "LINE"}
                  ],
                  "texts": [
                    {"layer": "FIRE", "text": "防火分区"}
                  ],
                  "dimensions": [
                    {"layer": "DOOR", "measurement": 1200}
                  ]
                }
                """);
    }
}

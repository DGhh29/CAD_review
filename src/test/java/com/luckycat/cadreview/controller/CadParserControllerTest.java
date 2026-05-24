package com.luckycat.cadreview.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.agent.CadIrCleaner;
import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.parser.CadParserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CadParserControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnCleanedJsonAfterParsingDxf() throws Exception {
        CadParserService cadParserService = mock(CadParserService.class);
        CadParserController controller = new CadParserController(
                cadParserService,
                new CadIrCleaner(objectMapper),
                objectMapper
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.dxf",
                "application/octet-stream",
                new byte[] {1, 2, 3}
        );
        when(cadParserService.parseDxf(any(MultipartFile.class))).thenReturn(sampleParsedIr());

        ApiResult<JsonNode> response = controller.parse(file);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().has("drawing")).isTrue();
        assertThat(response.getData().has("quality")).isTrue();
        assertThat(response.getData().has("clean_layers")).isTrue();
        assertThat(response.getData().has("clean_texts")).isTrue();
        assertThat(response.getData().has("evidence_groups")).isTrue();
        assertThat(response.getData().has("entities")).isFalse();
        assertThat(response.getData().path("drawing").path("summary").path("entity_count").asInt()).isEqualTo(1);
        assertThat(response.getData().path("evidence_groups").path("fire").path("texts").size()).isEqualTo(1);
        verify(cadParserService).parseDxf(any(MultipartFile.class));
    }

    private Map<String, Object> sampleParsedIr() throws Exception {
        return objectMapper.readValue("""
                {
                  "schema_version": "cad-drawing-parser.v1",
                  "success": true,
                  "summary": {
                    "entity_count": 1,
                    "bbox": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 100}
                  },
                  "layers": [
                    {"name": "FIRE", "entity_count": 1, "semantic": "fire", "is_off": false, "is_frozen": false}
                  ],
                  "blocks": [
                    {"name": "FIRE_BLOCK", "entity_count": 1, "insert_count": 1, "semantic": "fire"}
                  ],
                  "texts": [
                    {"layer": "FIRE", "text": "消防车道", "point": [10, 10, 0]}
                  ],
                  "dimensions": [
                    {"layer": "FIRE", "measurement": 6.0, "text": "", "point": [20, 10, 0]}
                  ],
                  "entities": [
                    {
                      "index": 1,
                      "handle": "A1",
                      "layer": "FIRE",
                      "type": "LINE",
                      "semantic": "fire",
                      "start": [0, 0, 0],
                      "end": [10, 0, 0]
                    }
                  ]
                }
                """, new TypeReference<>() {});
    }
}

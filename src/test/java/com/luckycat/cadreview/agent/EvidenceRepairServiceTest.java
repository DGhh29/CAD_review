package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRepairServiceTest {

    @Test
    void shouldChunkRawIrByFireFacilityHints() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RawIrChunker chunker = new RawIrChunker(objectMapper);
        var rawIr = objectMapper.readTree("""
                {
                  "texts": [
                    {"text": "消防水池", "layer": "SPACE", "position": [1, 2, 0]},
                    {"text": "普通说明", "layer": "PUB_TEXT", "position": [3, 4, 0]}
                  ],
                  "entities": [
                    {"type": "INSERT", "layer": "EQUIP_消火栓", "blockName": "HYDRANT", "position": [5, 6, 0]},
                    {"type": "LINE", "layer": "A-WALL", "position": [7, 8, 0]}
                  ]
                }
                """);
        EvidenceSearchTask task = EvidenceSearchTask.builder()
                .taskId("T-FIRE")
                .ruleId("FIRE_FACILITY_001")
                .missingEvidence(List.of("消火栓设施", "消防水池"))
                .keywords(List.of("消防水池", "消火栓"))
                .layerHints(List.of("EQUIP_消火栓"))
                .entityTypeHints(List.of("INSERT"))
                .maxChunks(10)
                .build();

        List<EvidenceChunk> chunks = chunker.chunk(rawIr, task);

        String json = objectMapper.writeValueAsString(chunks);
        assertThat(chunks).isNotEmpty();
        assertThat(json).contains("raw_ir.texts[0]", "raw_ir.entities[0]", "EQUIP_消火栓");
        assertThat(json).doesNotContain("A-WALL");
    }

    @Test
    void shouldGenerateEvidencePackForPendingFireFinding() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RawIrChunker chunker = new RawIrChunker(objectMapper);
        EvidenceReducer reducer = new EvidenceReducer();
        AgentProperties properties = new AgentProperties();
        EvidenceExtractionClient extractor = (task, chunk) -> EvidenceExtractionResult.builder()
                .taskId(task.getTaskId())
                .chunkId(chunk.getChunkId())
                .relevant(true)
                .evidence(chunk.getItems().stream()
                        .map(item -> ExtractedEvidence.builder()
                                .evidenceType(chunk.getChunkType())
                                .sourcePath(item.path("sourcePath").asText())
                                .layer(item.path("layer").asText())
                                .entityType(item.path("type").asText())
                                .content(item.path("text").asText(item.path("blockName").asText("消火栓图块")))
                                .matchedRequirement("消火栓设施")
                                .confidence(0.9d)
                                .build())
                        .toList())
                .stillMissing(List.of("喷淋系统线索"))
                .build();
        EvidenceRepairService service = new EvidenceRepairService(chunker, extractor, reducer, properties, Runnable::run);
        var rawIr = objectMapper.readTree("""
                {
                  "entities": [
                    {"type": "INSERT", "layer": "EQUIP_消火栓", "blockName": "HYDRANT", "position": [5, 6, 0]}
                  ]
                }
                """);
        ReviewTask task = ReviewTask.builder()
                .taskId("T-FIRE")
                .checkItem("消防设施布置")
                .ruleIds(List.of("FIRE_FACILITY_001"))
                .evidenceGroups(List.of("fire"))
                .build();
        ReviewRule rule = ReviewRule.builder()
                .id("FIRE_FACILITY_001")
                .clauseId("GB50016-8")
                .title("消防设施布置")
                .scope("消防 消火栓 喷淋 报警")
                .promptFragment("审核消防设施图元")
                .version("v1")
                .requiredEvidence(List.of("消火栓设施", "喷淋系统线索"))
                .build();
        Finding finding = Finding.builder()
                .verdict(Verdict.PENDING_REVIEW)
                .ruleId("FIRE_FACILITY_001")
                .reason("未看到消火栓图块")
                .missingEvidence(List.of("消火栓设施"))
                .repairHints(List.of("EQUIP_消火栓"))
                .build();

        EvidenceRepairResult result = service.repair("run-1", rawIr, task, List.of(rule), List.of(finding), 1);

        assertThat(result.hasUsefulEvidence()).isTrue();
        assertThat(result.getEvidencePack().getFoundEvidence()).hasSize(1);
        assertThat(result.getEvidencePack().getFoundEvidence().get(0).getSourcePath()).isEqualTo("raw_ir.entities[0]");
        assertThat(result.getEvidencePack().getMissingEvidence()).contains("喷淋系统线索");
    }
}

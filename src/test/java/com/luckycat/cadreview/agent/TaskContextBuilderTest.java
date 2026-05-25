package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.metrics.CadGeometryMetricsService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskContextBuilderTest {

    @Test
    void shouldRouteParkingTaskToParkingEvidenceGroup() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentProperties properties = new AgentProperties();
        CadIrCleaner cleaner = new CadIrCleaner(objectMapper);
        IrViewService irViewService = new IrViewService(objectMapper, properties, cleaner);
        ContextBudgetService budgetService = new ContextBudgetService(objectMapper, properties);
        PreCleanerAgent preCleanerAgent = new PreCleanerAgent(objectMapper, budgetService);
        TaskContextBuilder builder = new TaskContextBuilder(
                objectMapper, cleaner, irViewService, preCleanerAgent, CadGeometryMetricsService.createDefault(objectMapper));

        var drawingIr = objectMapper.readTree("""
                {
                  "summary": {"entity_count": 1},
                  "statistics": {},
                  "semantic": {},
                  "texts": [
                    {"text": "充电桩停车位", "layer": "PUB_TEXT", "position": [1, 1]},
                    {"text": "22", "layer": "PUB_TEXT", "position": [2, 1]}
                  ],
                  "dimensions": [],
                  "entities": [],
                  "layers": [{"name": "PUB_TEXT", "entity_count": 2}]
                }
                """);
        ReviewTask task = ReviewTask.builder()
                .taskId("TASK-PARKING")
                .checkItem("充电停车位审查")
                .ruleIds(List.of("PARKING-1"))
                .category("parking")
                .build();
        ReviewRule rule = ReviewRule.builder()
                .id("PARKING-1")
                .clauseId("LOCAL-PARKING")
                .title("充电停车位")
                .scope("停车和充电设施")
                .promptFragment("审查充电停车位数量")
                .version("v1")
                .build();

        var context = builder.build(drawingIr, task, List.of(rule));

        assertThat(context.path("context").path("evidence_groups").has("parking")).isTrue();
        assertThat(context.path("context").toString()).contains("充电桩停车位");
    }

    @Test
    void shouldRouteRoadRadiusRuleToRoadDimensionAndBoundaryGroups() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskContextBuilder builder = newBuilder(objectMapper);

        var drawingIr = objectMapper.readTree("""
                {
                  "summary": {"entity_count": 2},
                  "statistics": {},
                  "semantic": {},
                  "texts": [{"text": "内部道路 R=6m", "layer": "PUB_TEXT", "position": [1, 1]}],
                  "dimensions": [{"text": "4.00", "layer": "PUB_DIM"}],
                  "entities": [],
                  "layers": [{"name": "内部道路", "entity_count": 2}]
                }
                """);
        ReviewTask task = ReviewTask.builder()
                .taskId("TASK-ROAD")
                .checkItem("内部道路转弯半径")
                .ruleIds(List.of("R-RD-002"))
                .category("道路")
                .build();
        ReviewRule rule = ReviewRule.builder()
                .id("R-RD-002")
                .clauseId("GB50187-2012-6.4.2")
                .title("厂区道路转弯半径")
                .scope("道路 转弯半径 R=")
                .promptFragment("道路转弯半径不应小于 6m")
                .version("seed-2025-v1")
                .build();

        var evidenceGroups = builder.build(drawingIr, task, List.of(rule)).path("context").path("evidence_groups");

        assertThat(evidenceGroups.has("road")).isTrue();
        assertThat(evidenceGroups.has("dimensions")).isTrue();
        assertThat(evidenceGroups.has("site_boundary")).isTrue();

        var computedMetrics = builder.build(drawingIr, task, List.of(rule)).path("context").path("computed_metrics");
        assertThat(computedMetrics.toString()).contains("MEASURE_RADIUS", "R-RD-002");
    }

    @Test
    void shouldRouteSunAndVerticalRulesToRelatedEvidenceGroups() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskContextBuilder builder = newBuilder(objectMapper);

        var drawingIr = objectMapper.readTree("""
                {
                  "summary": {"entity_count": 2},
                  "statistics": {},
                  "semantic": {},
                  "texts": [{"text": "日照分析报告", "layer": "PUB_TEXT", "position": [1, 1]}],
                  "dimensions": [],
                  "entities": [],
                  "layers": [{"name": "DIM_ELEV", "entity_count": 2}]
                }
                """);
        ReviewTask task = ReviewTask.builder()
                .taskId("TASK-SUN-VERTICAL")
                .checkItem("日照分析与竖向标高复核")
                .ruleIds(List.of("R-SUN-001", "R-VRT-001"))
                .category("规划")
                .build();
        List<ReviewRule> rules = List.of(
                ReviewRule.builder()
                        .id("R-SUN-001")
                        .clauseId("NNZRZY-2018-SUPPL-7")
                        .title("日照分析报告")
                        .scope("日照分析 日照承诺书")
                        .promptFragment("审核是否存在日照分析报告")
                        .version("seed-2025-v1")
                        .build(),
                ReviewRule.builder()
                        .id("R-VRT-001")
                        .clauseId("NNZRZY-2018-SUPPL-6")
                        .title("竖向设计与场地高差专项方案")
                        .scope("竖向 高差 标高")
                        .promptFragment("场地竖向高差超过 5m 时应提交专项方案")
                        .version("seed-2025-v1")
                        .build());

        var evidenceGroups = builder.build(drawingIr, task, rules).path("context").path("evidence_groups");

        assertThat(evidenceGroups.has("building_info")).isTrue();
        assertThat(evidenceGroups.has("site_boundary")).isTrue();
        assertThat(evidenceGroups.has("dimensions")).isTrue();
    }

    @Test
    void shouldInjectEvidencePackIntoRebuildContext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskContextBuilder builder = newBuilder(objectMapper);
        var drawingIr = objectMapper.readTree("""
                {
                  "summary": {"entity_count": 1},
                  "statistics": {},
                  "semantic": {},
                  "texts": [],
                  "dimensions": [],
                  "entities": [],
                  "layers": []
                }
                """);
        ReviewTask task = ReviewTask.builder()
                .taskId("TASK-FIRE")
                .checkItem("消防设施")
                .ruleIds(List.of("FIRE_FACILITY_001"))
                .category("消防")
                .build();
        ReviewRule rule = ReviewRule.builder()
                .id("FIRE_FACILITY_001")
                .clauseId("GB50016-8")
                .title("消防设施")
                .scope("消防设施")
                .promptFragment("审核消防设施")
                .version("v1")
                .build();
        TaskEvidencePack pack = TaskEvidencePack.builder()
                .taskId("TASK-FIRE")
                .ruleId("FIRE_FACILITY_001")
                .foundEvidence(List.of(ExtractedEvidence.builder()
                        .sourcePath("raw_ir.entities[0]")
                        .layer("EQUIP_消火栓")
                        .content("消火栓图块")
                        .confidence(0.9d)
                        .build()))
                .quality(TaskEvidencePack.Quality.builder()
                        .evidenceCount(1)
                        .sourceTraceable(true)
                        .confidence(0.9d)
                        .repairStatus("FOUND_PARTIAL_EVIDENCE")
                        .build())
                .build();

        var context = builder.buildWithEvidencePack(drawingIr, task, List.of(rule), pack);

        assertThat(context.path("context").path("evidencePack").path("foundEvidence").toString())
                .contains("raw_ir.entities[0]", "EQUIP_消火栓");
        assertThat(context.path("context").path("evidenceRepair").path("repairAttempted").asBoolean()).isTrue();
    }

    private TaskContextBuilder newBuilder(ObjectMapper objectMapper) {
        AgentProperties properties = new AgentProperties();
        CadIrCleaner cleaner = new CadIrCleaner(objectMapper);
        IrViewService irViewService = new IrViewService(objectMapper, properties, cleaner);
        ContextBudgetService budgetService = new ContextBudgetService(objectMapper, properties);
        PreCleanerAgent preCleanerAgent = new PreCleanerAgent(objectMapper, budgetService);
        return new TaskContextBuilder(objectMapper, cleaner, irViewService, preCleanerAgent,
                CadGeometryMetricsService.createDefault(objectMapper));
    }
}

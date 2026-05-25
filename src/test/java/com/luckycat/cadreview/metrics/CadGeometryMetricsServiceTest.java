package com.luckycat.cadreview.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CadGeometryMetricsServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CadGeometryMetricsService service = CadGeometryMetricsService.createDefault(objectMapper);

    @Test
    void shouldCalculateFirstWaveMetrics() throws Exception {
        var ir = objectMapper.readTree("""
                {
                  "texts": [
                    {"text": "日照分析报告", "layer": "PUB_TEXT", "point": [0, 0, 0]},
                    {"text": "机动车停车位", "layer": "PUB_TEXT", "point": [0, 20, 0]},
                    {"text": "62", "layer": "PUB_TEXT", "point": [100, 20, 0]},
                    {"text": "标高 10.00", "layer": "DIM_ELEV", "point": [0, 40, 0]},
                    {"text": "标高 16.20", "layer": "DIM_ELEV", "point": [100, 40, 0]},
                    {"text": "R=6000", "layer": "PUB_TEXT", "point": [0, 60, 0]}
                  ],
                  "dimensions": [
                    {"layer": "PUB_DIM", "measurement": 4000, "text": "4.00m", "point": [0, 80, 0]}
                  ],
                  "entities": [
                    {"index": 1, "handle": "A1", "type": "ARC", "layer": "ROAD", "center": [0, 0, 0], "radius": 6000}
                  ],
                  "blocks": []
                }
                """);

        List<CadMetricResult> results = service.calculate(ir, List.of(
                request("text", MetricOperation.FIND_TEXT, target(List.of("日照分析")), 1.0, MetricComparator.EXISTS),
                request("dim", MetricOperation.EXTRACT_DIMENSION, layerTarget(List.of("PUB_DIM")), 4.0, MetricComparator.GREATER_OR_EQUAL),
                request("radius", MetricOperation.MEASURE_RADIUS, target(List.of("R=")), 6.0, MetricComparator.GREATER_OR_EQUAL),
                request("count", MetricOperation.COUNT_OBJECTS, target(List.of("机动车停车位")), 1.0, MetricComparator.GREATER_OR_EQUAL),
                request("elevation", MetricOperation.MEASURE_ELEVATION_DIFF, target(List.of("标高")), 5.0, MetricComparator.LESS_OR_EQUAL)
        ));

        assertThat(results).extracting(CadMetricResult::getRequestId)
                .containsExactly("text", "dim", "radius", "count", "elevation");
        assertThat(byId(results, "text").getComparison()).isEqualTo("PASS");
        assertThat(byId(results, "dim").getMeasuredValue()).isEqualTo(4.0);
        assertThat(byId(results, "radius").getMeasuredValue()).isEqualTo(6.0);
        assertThat(byId(results, "count").getMeasuredValue()).isEqualTo(62.0);
        assertThat(byId(results, "elevation").getMeasuredValue()).isCloseTo(6.2, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(byId(results, "elevation").getComparison()).isEqualTo("FAIL");
        assertThat(byId(results, "radius").getEvidenceRefs()).isNotEmpty();
    }

    @Test
    void shouldCalculateSecondWaveMetrics() throws Exception {
        var ir = objectMapper.readTree("""
                {
                  "texts": [
                    {"text": "无障碍停车位", "layer": "PUB_TEXT", "point": [0, 20, 0]},
                    {"text": "2", "layer": "PUB_TEXT", "point": [100, 20, 0]},
                    {"text": "机动车停车位", "layer": "PUB_TEXT", "point": [0, 40, 0]},
                    {"text": "62", "layer": "PUB_TEXT", "point": [100, 40, 0]}
                  ],
                  "dimensions": [
                    {"layer": "ROAD_DIM", "measurement": 4000, "text": "道路宽度 4.00m", "point": [0, 80, 0]}
                  ],
                  "entities": [
                    {"index": 1, "handle": "D1", "type": "LINE", "layer": "DIST", "start": [0, 0, 0], "end": [0, 10, 0], "bbox": {"min_x": 0, "min_y": 0, "max_x": 0, "max_y": 10}},
                    {"index": 2, "handle": "D2", "type": "LINE", "layer": "DIST", "start": [5, 0, 0], "end": [5, 10, 0], "bbox": {"min_x": 5, "min_y": 0, "max_x": 5, "max_y": 10}},
                    {"index": 3, "handle": "P1", "type": "LWPOLYLINE", "layer": "FIRE_ZONE", "closed": true, "points": [[0, 0], [10, 0], [10, 10], [0, 10]]},
                    {"index": 4, "handle": "N1", "type": "LINE", "layer": "ROAD_NET", "start": [0, 0, 0], "end": [10, 0, 0]},
                    {"index": 5, "handle": "N2", "type": "LINE", "layer": "ROAD_NET", "start": [10, 0, 0], "end": [10, 10, 0]}
                  ],
                  "blocks": []
                }
                """);

        CadMetricTarget accessible = target(List.of("无障碍停车位"));
        CadMetricTarget total = target(List.of("机动车停车位"));
        List<CadMetricResult> results = service.calculate(ir, List.of(
                request("width", MetricOperation.MEASURE_WIDTH, layerTarget(List.of("ROAD_DIM")), 4.0, MetricComparator.GREATER_OR_EQUAL),
                request("distance", MetricOperation.MEASURE_DISTANCE, layerTarget(List.of("DIST")), 5.0, MetricComparator.GREATER_OR_EQUAL),
                request("area", MetricOperation.MEASURE_AREA, layerTarget(List.of("FIRE_ZONE")), 100.0, MetricComparator.LESS_OR_EQUAL),
                CadMetricRequest.builder()
                        .requestId("ratio")
                        .operation(MetricOperation.COMPUTE_RATIO)
                        .numeratorTarget(accessible)
                        .denominatorTarget(total)
                        .requiredValue(0.02)
                        .comparator(MetricComparator.GREATER_OR_EQUAL)
                        .build(),
                request("connectivity", MetricOperation.CHECK_CONNECTIVITY, layerTarget(List.of("ROAD_NET")), 1.0, MetricComparator.EQUAL)
        ));

        assertThat(byId(results, "width").getMeasuredValue()).isEqualTo(4.0);
        assertThat(byId(results, "distance").getMeasuredValue()).isEqualTo(5.0);
        assertThat(byId(results, "area").getMeasuredValue()).isEqualTo(100.0);
        assertThat(byId(results, "ratio").getMeasuredValue()).isCloseTo(2.0 / 62.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(byId(results, "ratio").getComparison()).isEqualTo("PASS");
        assertThat(byId(results, "connectivity").getMeasuredValue()).isEqualTo(1.0);
    }

    @Test
    void shouldTreatWeakGeometryCandidatesAsPartialEvidence() throws Exception {
        var ir = objectMapper.readTree("""
                {
                  "texts": [],
                  "dimensions": [],
                  "entities": [
                    {"index": 1, "handle": "R1", "type": "ARC", "layer": "ROAD", "center": [0, 0, 0], "radius": 150},
                    {"index": 2, "handle": "W1", "type": "LINE", "layer": "ROAD_WIDTH", "length": 150, "bbox": {"min_x": 0, "min_y": 0, "max_x": 150, "max_y": 0}},
                    {"index": 3, "handle": "A1", "type": "LINE", "layer": "PLOT", "bbox": {"min_x": 0, "min_y": 0, "max_x": 2000, "max_y": 2000}}
                  ],
                  "blocks": []
                }
                """);

        List<CadMetricResult> results = service.calculate(ir, List.of(
                request("weak-radius", MetricOperation.MEASURE_RADIUS, layerTarget(List.of("ROAD")), 6.0, MetricComparator.GREATER_OR_EQUAL),
                request("weak-width", MetricOperation.MEASURE_WIDTH, layerTarget(List.of("ROAD_WIDTH")), 4.0, MetricComparator.GREATER_OR_EQUAL),
                request("weak-area", MetricOperation.MEASURE_AREA, layerTarget(List.of("PLOT")), 100.0, MetricComparator.LESS_OR_EQUAL)
        ));

        assertThat(byId(results, "weak-radius").getStatus()).isEqualTo(MetricStatus.PARTIAL);
        assertThat(byId(results, "weak-width").getStatus()).isEqualTo(MetricStatus.PARTIAL);
        assertThat(byId(results, "weak-area").getStatus()).isEqualTo(MetricStatus.PARTIAL);
        assertThat(byId(results, "weak-radius").getComparison()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(byId(results, "weak-width").getComparison()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(byId(results, "weak-area").getComparison()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(byId(results, "weak-radius").getMeasuredValue()).isNull();
        assertThat(byId(results, "weak-width").getMeasuredValue()).isNull();
        assertThat(byId(results, "weak-area").getMeasuredValue()).isNull();
        assertThat(byId(results, "weak-radius").getDetails().path("evidenceStrength").asText()).isEqualTo("WEAK_GEOMETRY");
        assertThat(byId(results, "weak-radius").getDetails().path("minRadius").asDouble()).isEqualTo(0.15);
    }

    private CadMetricRequest request(
            String id,
            MetricOperation operation,
            CadMetricTarget target,
            Double requiredValue,
            MetricComparator comparator) {
        return CadMetricRequest.builder()
                .requestId(id)
                .operation(operation)
                .target(target)
                .requiredValue(requiredValue)
                .comparator(comparator)
                .build();
    }

    private CadMetricTarget target(List<String> textHints) {
        return CadMetricTarget.builder().textHints(textHints).build();
    }

    private CadMetricTarget layerTarget(List<String> layerHints) {
        return CadMetricTarget.builder().layerHints(layerHints).build();
    }

    private CadMetricResult byId(List<CadMetricResult> results, String id) {
        return results.stream()
                .filter(result -> id.equals(result.getRequestId()))
                .findFirst()
                .orElseThrow();
    }
}

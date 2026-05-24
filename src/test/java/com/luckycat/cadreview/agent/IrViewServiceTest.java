package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IrViewService} 单元测试。
 *
 * <p>IrViewService 是 Dispatcher / Reviewer 与原始 IR 之间的视图层，
 * 负责两件相互配合的事：
 * <ol>
 *   <li><b>buildSummary</b> —— 给 Dispatcher 的"全图概览"，会拿掉巨大的 entities 数组、
 *       插入清洗后的 review_context，避免上下文超长</li>
 *   <li><b>slice</b> —— 给 Reviewer 的"按需切片"，根据 entityIds / layerNames
 *       只截取相关图元，控制每次 LLM 调用的 token 开销</li>
 * </ol>
 *
 * <p>覆盖场景：摘要不携带原始 entities、按 entity / layer 切片是否准确、
 * 真实清洗器（CadIrCleaner）输出的 review_context 在 evidence_groups
 * 与 quality.dropped_counts 上的关键字段是否符合预期。
 */
class IrViewServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证 buildSummary 不再回吐完整 entities 数组（它太大），
     * 但会保留固定数量的 entitySamples 与全部 layers，
     * 同时挂上一份清洗后的 review_context 子树。
     */
    @Test
    void shouldBuildSummaryWithoutFullEntityArray() throws Exception {
        IrViewService service = new IrViewService(objectMapper, buildProperties(), new CadIrCleaner(objectMapper));
        JsonNode ir = sampleIr();

        JsonNode summary = service.buildSummary(ir);

        assertThat(summary.has("entities")).isFalse();
        assertThat(summary.has("review_context")).isTrue();
        assertThat(summary.path("entitySamples").size()).isEqualTo(2);
        assertThat(summary.path("layers").size()).isEqualTo(2);
    }

    /**
     * 验证两种切片维度互不串扰：
     * 按 entityId=A1 应只命中 LWPOLYLINE 一条；
     * 按 layerName=DOOR 应只命中 LINE 一条，且能把同图层的 dimensions 一并带出。
     */
    @Test
    void shouldSliceByEntityIdsAndLayerNames() throws Exception {
        IrViewService service = new IrViewService(objectMapper, buildProperties(), new CadIrCleaner(objectMapper));
        JsonNode ir = sampleIr();

        JsonNode byEntity = service.slice(ir, List.of("A1"), List.of());
        JsonNode byLayer = service.slice(ir, List.of(), List.of("DOOR"));

        assertThat(byEntity.path("selectedEntities").size()).isEqualTo(1);
        assertThat(byEntity.path("selectedEntities").get(0).path("handle").asText()).isEqualTo("A1");
        assertThat(byLayer.path("selectedEntities").size()).isEqualTo(1);
        assertThat(byLayer.path("selectedEntities").get(0).path("handle").asText()).isEqualTo("B2");
        assertThat(byLayer.path("selectedDimensions").size()).isEqualTo(1);
    }

    /**
     * 验证 review_context 的清洗规则在一份精心构造的多图层 IR 上的真实表现：
     * <ul>
     *   <li>关闭/冻结 + 0 实体的图层会被清理（4 个原始 layer → 2 个 clean_layers）</li>
     *   <li>insert_count=0 的孤儿块被剔除，只剩真正被引用过的</li>
     *   <li>纯数字 / 编号类的 text 会被识别为噪声丢弃</li>
     *   <li>"消防车道""车位"等关键字进入 evidence_groups.fire / parking 分组</li>
     *   <li>quality.dropped_counts 准确记录每类被清理的数量</li>
     * </ul>
     */
    @Test
    void shouldBuildCleanReviewContext() throws Exception {
        IrViewService service = new IrViewService(objectMapper, buildProperties(), new CadIrCleaner(objectMapper));
        JsonNode ir = objectMapper.readTree("""
                {
                  "schema_version": "cad-drawing-parser.v1",
                  "success": true,
                  "summary": {"entity_count": 4, "entity_truncated": false},
                  "layers": [
                    {"name": "PUB_TEXT", "entity_count": 2, "is_off": false, "is_frozen": false},
                    {"name": "DEFAULT", "entity_count": 1, "is_off": true, "is_frozen": true},
                    {"name": "EMPTY", "entity_count": 0, "is_off": false, "is_frozen": false},
                    {"name": "车位", "entity_count": 1, "semantic": "parking", "is_off": false, "is_frozen": false}
                  ],
                  "blocks": [
                    {"name": "unused", "entity_count": 10, "insert_count": 0},
                    {"name": "$LinePat$挡土墙", "entity_count": 2, "insert_count": 3, "semantic": "walls"}
                  ],
                  "texts": [
                    {"layer": "DEFAULT", "text": "01CNU0", "point": [0, 0, 0]},
                    {"layer": "PUB_TEXT", "text": "消防车道", "point": [10, 10, 0]},
                    {"layer": "PUB_TEXT", "text": "12", "point": [12, 10, 0]},
                    {"layer": "PUB_TEXT", "text": "99", "point": [5000, 5000, 0]}
                  ],
                  "dimensions": [
                    {"layer": "PUB_DIM", "measurement": 6.0, "text": "", "point": [11, 10, 0]}
                  ],
                  "entities": [
                    {"index": 1, "handle": "A1", "layer": "车位", "type": "INSERT", "semantic": "parking", "insert": [10, 10, 0]},
                    {"index": 2, "handle": "A2", "layer": "DEFAULT", "type": "TEXT", "text": "01CNU0", "insert": [10, 10, 0]},
                    {"index": 3, "handle": "A3", "layer": "PUB_TEXT", "type": "LINE", "start": [5000, 5000, 0], "end": [5010, 5010, 0]}
                  ]
                }
                """);

        JsonNode context = service.buildSummary(ir).path("review_context");

        assertThat(context.path("clean_layers").size()).isEqualTo(2);
        assertThat(context.path("clean_blocks").size()).isEqualTo(1);
        assertThat(context.path("clean_texts").size()).isEqualTo(2);
        assertThat(context.path("evidence_groups").path("fire").path("texts").size()).isEqualTo(2);
        assertThat(context.path("evidence_groups").path("parking").path("entities").size()).isEqualTo(1);
        assertThat(context.path("quality").path("dropped_counts").path("unused_blocks").asInt()).isEqualTo(1);
    }

    /** 构造一个限制 maxReviewEntities=10 的 AgentProperties，避免切片函数被默认 0 截断。 */
    private AgentProperties buildProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxReviewEntities(10);
        return properties;
    }

    /** 简单 IR：两个 layer × 两个 entity × 一条 text + 一条 dimension，足以验证切片维度。 */
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

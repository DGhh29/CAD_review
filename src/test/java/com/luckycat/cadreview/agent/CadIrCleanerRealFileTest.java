package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CadIrCleaner} 真实文件级集成测试。
 *
 * <p>这是一个<b>依赖本机真实 .dxf/.dwg 解析产物</b>的集成测试，默认输入路径
 * 为 {@code D:\workspace\oDev\解析产物\解析结果.json}。
 * 若文件不存在，使用 {@link org.junit.jupiter.api.Assumptions#assumeTrue} 直接跳过，
 * 因此在 CI 环境（无真实文件）下会被静默忽略，
 * <b>仅供开发者本地手动验证清洗效果</b>。
 *
 * <p>断言只做粗粒度的"非空 + 计数大于 0"校验，更精细的语义校验
 * 需要在确定的小样本数据上跑（见 {@link IrViewServiceTest}），
 * 这里更多是回归监控用途：保证清洗器在真实复杂图纸上不会输出空结构。
 */
class CadIrCleanerRealFileTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 读取本地解析 JSON、跑一遍清洗、把结果落盘并对关键节点做计数断言。
     * 找不到输入文件时跳过测试，避免阻塞 CI。
     */
    @Test
    void shouldCleanRealParsedJsonFileAndWriteReviewContext() throws Exception {
        Path inputPath = Path.of(System.getProperty(
                "cad.cleaner.input",
                "D:\\workspace\\oDev\\解析产物\\解析结果.json"
        ));
        // 没有真实文件就跳过，本测试只在本地可用
        assumeTrue(Files.exists(inputPath), () -> "解析结果文件不存在，跳过真实文件清洗测试: " + inputPath);

        JsonNode root = objectMapper.readTree(inputPath.toFile());
        // 兼容包 data 的 API 响应与裸 IR 两种格式
        JsonNode drawingIr = root.has("data") ? root.path("data") : root;

        JsonNode reviewContext = new CadIrCleaner(objectMapper).buildReviewContext(drawingIr);

        Path outputPath = Path.of(System.getProperty(
                "cad.cleaner.output",
                "target/test-output/cleaned-review-context.json"
        )).toAbsolutePath();
        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), reviewContext);

        // 关键断言：实体计数 > 0 / 文本非空 / 消防证据组非空 / 至少剔除了一个 unused block
        assertThat(reviewContext.path("drawing").path("summary").path("entity_count").asInt()).isGreaterThan(0);
        assertThat(reviewContext.path("clean_texts").size()).isGreaterThan(0);
        assertThat(reviewContext.path("evidence_groups").path("fire").path("texts").size()).isGreaterThan(0);
        assertThat(reviewContext.path("quality").path("dropped_counts").path("unused_blocks").asInt()).isGreaterThan(0);

        System.out.println("清洗结果已写入: " + outputPath);
        System.out.println("clean_layers=" + reviewContext.path("clean_layers").size()
                + ", clean_blocks=" + reviewContext.path("clean_blocks").size()
                + ", clean_texts=" + reviewContext.path("clean_texts").size()
                + ", clean_dimensions=" + reviewContext.path("clean_dimensions").size()
                + ", clean_entity_samples=" + reviewContext.path("clean_entity_samples").size());
    }
}

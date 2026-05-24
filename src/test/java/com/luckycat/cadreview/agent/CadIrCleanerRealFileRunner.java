package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 真实文件场景下 {@link CadIrCleaner} 的本地手动 Runner（非自动化测试）。
 *
 * <p>本类是带 main 方法的可执行入口，不会被 JUnit 自动发现，
 * 用途是在调试 IR 清洗规则时，把本地解析产物（默认 {@code D:\workspace\oDev\解析产物\解析结果.json}）
 * 跑一遍 {@link CadIrCleaner#buildReviewContext} 并把结果写到 {@code target/test-output/} 下，
 * 方便人工对照查看清洗后的 layers / blocks / texts / dimensions / entity samples。
 *
 * <p>因为强依赖本机真实文件，<b>CI 不应执行</b>，仅供开发者本地复盘清洗逻辑。
 * 输入路径与输出路径都可以通过 {@code -Dcad.cleaner.input} / {@code -Dcad.cleaner.output} 覆盖。
 */
public class CadIrCleanerRealFileRunner {

    /**
     * 读取本地解析 JSON、调用清洗器、把结果写到 target 目录，并在 stdout 打印各类目计数。
     * 输入文件不存在时直接抛 IllegalArgumentException —— 强制开发者先准备好真实数据。
     */
    public static void main(String[] args) throws Exception {
        Path inputPath = Path.of(System.getProperty(
                "cad.cleaner.input",
                "D:\\workspace\\oDev\\解析产物\\解析结果.json"
        ));
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("解析结果文件不存在: " + inputPath);
        }

        Path outputPath = Path.of(System.getProperty(
                "cad.cleaner.output",
                "target/test-output/cleaned-review-context.json"
        )).toAbsolutePath();

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(inputPath.toFile());
        // 兼容两种 JSON 结构：包了一层 data 的 API 响应，与直接就是 IR 的裸文件
        JsonNode drawingIr = root.has("data") ? root.path("data") : root;
        JsonNode reviewContext = new CadIrCleaner(objectMapper).buildReviewContext(drawingIr);

        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), reviewContext);

        System.out.println("清洗结果已写入: " + outputPath);
        System.out.println("clean_layers=" + reviewContext.path("clean_layers").size()
                + ", clean_blocks=" + reviewContext.path("clean_blocks").size()
                + ", clean_texts=" + reviewContext.path("clean_texts").size()
                + ", clean_dimensions=" + reviewContext.path("clean_dimensions").size()
                + ", clean_entity_samples=" + reviewContext.path("clean_entity_samples").size());
    }
}

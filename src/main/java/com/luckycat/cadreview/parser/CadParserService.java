package com.luckycat.cadreview.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * CAD 解析对外服务入口。
 *
 * <p>负责把上传的 {@link MultipartFile} 落地成临时文件，
 * 交给 {@link HttpParserClient} 调外部 HTTP 解析器，最后清理临时文件。
 * 解析结果是一个 {@code Map<String, Object>}（即 IR JSON），
 * 后续由 {@code agent} 包转成 {@code JsonNode} 进入审图流水线。
 *
 * <p>调用方：
 * <ul>
 *   <li>{@link com.luckycat.cadreview.agent.AgentOrchestrator#parseDrawing}
 *       ——评审主链路</li>
 *   <li>{@link com.luckycat.cadreview.controller.CadParserController}
 *       ——独立的解析 REST 接口，便于调试</li>
 * </ul>
 *
 * <p>所有失败都以 {@link CadParseException} 抛出，
 * 由 {@link com.luckycat.cadreview.common.GlobalExceptionHandler} 统一处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CadParserService {

    private final HttpParserClient httpParserClient;

    /**
     * 解析 DXF 文件。
     *
     * @param file 上传的 .dxf 文件，后缀会被严格校验
     * @return 外部解析服务返回的 IR JSON（已反序列化为 Map）
     * @throws IllegalArgumentException 文件名缺失或后缀不是 .dxf
     * @throws CadParseException        临时文件落盘失败 / HTTP 解析失败 / 响应解析失败
     */
    public Map<String, Object> parseDxf(MultipartFile file) {
        validateExtension(file, ".dxf");
        return parseInternal(file, ".dxf");
    }

    /**
     * 解析 DWG 文件。逻辑同 {@link #parseDxf}，仅校验后缀不同。
     */
    public Map<String, Object> parseDwg(MultipartFile file) {
        validateExtension(file, ".dwg");
        return parseInternal(file, ".dwg");
    }

    /**
     * 通用的"落地临时文件 → 调 HTTP 解析 → 清理临时文件"模板。
     *
     * <p>无论解析成功与否，{@code finally} 都会尝试删除临时文件，
     * 删除失败仅打 warn 日志，不影响主流程。
     */
    private Map<String, Object> parseInternal(MultipartFile file, String suffix) {
        Path tempFile = saveToTemp(file, suffix);
        try {
            return httpParserClient.parse(tempFile);
        } finally {
            deleteSilently(tempFile);
        }
    }

    private void validateExtension(MultipartFile file, String expected) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(expected)) {
            throw new IllegalArgumentException("Expected " + expected + " file, got: " + originalName);
        }
    }

    /**
     * 把 {@link MultipartFile} 写到系统临时目录。
     * 之所以要落地是因为 {@link HttpParserClient} 需要把文件按 multipart/form-data 发出去，
     * 直接从 InputStream 在某些 RestClient 实现下不便控制大小。
     *
     * @throws CadParseException IO 失败
     */
    private Path saveToTemp(MultipartFile file, String suffix) {
        try {
            Path temp = Files.createTempFile("cad-upload-", suffix);
            file.transferTo(temp.toFile());
            return temp;
        } catch (IOException e) {
            throw new CadParseException("Failed to save uploaded file", e);
        }
    }

    /** 删除临时文件，失败只记 warn，不影响主链路。 */
    private void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", path, e);
        }
    }
}

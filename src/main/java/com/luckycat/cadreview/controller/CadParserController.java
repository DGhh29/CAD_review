package com.luckycat.cadreview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.agent.CadIrCleaner;
import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.parser.CadParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * CAD 文件解析 REST 入口，路径前缀 {@code /api/cad}。
 *
 * <p>上传 .dxf / .dwg 图纸后，先调用独立 Python HTTP 解析器拿到原始图元 IR，
 * 再立即通过 {@link CadIrCleaner} 清洗为面向审图和前端展示的结构化 JSON。
 *
 * <p>本控制器只负责“解析 + 清洗”，不会调用 LLM，也不会生成审核结论。
 * 真正的多 Agent 审核入口在 {@code ReviewController}。
 */
@RestController
@RequestMapping("/api/cad")
@RequiredArgsConstructor
public class CadParserController {

    private final CadParserService cadParserService;
    private final CadIrCleaner cadIrCleaner;
    private final ObjectMapper objectMapper;

    /**
     * 解析上传的 CAD 文件：{@code POST /api/cad/parse}。
     *
     * <p>根据文件扩展名分发到对应解析路径：
     * <ul>
     *   <li>{@code .dxf} -> {@link CadParserService#parseDxf(MultipartFile)}</li>
     *   <li>{@code .dwg} -> {@link CadParserService#parseDwg(MultipartFile)}</li>
     * </ul>
     * 其它后缀会直接抛 {@link IllegalArgumentException}，由全局异常处理转换成 4xx。
     *
     * <p>返回值不再包含原始全量 IR，而是清洗完成后的结构化 JSON，顶层包含
     * {@code drawing}、{@code quality}、{@code clean_layers}、{@code clean_texts}、
     * {@code clean_dimensions}、{@code clean_entity_samples}、{@code evidence_groups} 等字段。
     *
     * @param file 必须为 multipart 字段 {@code file}，扩展名只接受 .dxf / .dwg
     * @return 清洗完成后的 CAD 结构化 JSON
     */
    @PostMapping("/parse")
    public ApiResult<JsonNode> parse(@RequestParam("file") MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IllegalArgumentException("File name is required");
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new IllegalArgumentException("File extension is required, only .dxf and .dwg are supported");
        }
        String ext = fileName.substring(dotIndex).toLowerCase();
        Map<String, Object> result = switch (ext) {
            case ".dxf" -> cadParserService.parseDxf(file);
            case ".dwg" -> cadParserService.parseDwg(file);
            default -> throw new IllegalArgumentException("Unsupported file type: " + ext + ", only .dxf and .dwg are supported");
        };
        JsonNode cleanedResult = cadIrCleaner.buildReviewContext(objectMapper.valueToTree(result));
        return ApiResult.ok(cleanedResult);
    }
}

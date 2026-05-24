package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.KnowledgeSearchRequest;
import com.luckycat.cadreview.dto.KnowledgeSearchResult;
import com.luckycat.cadreview.dto.KnowledgeUploadResponse;
import com.luckycat.cadreview.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库（规范条文）管理 REST 入口，路径前缀 {@code /api/knowledge}。
 *
 * <p>提供向量化知识库的三类操作给前端使用：
 * <ul>
 *   <li>上传规范文档：经 Tika 解析 + Token 切片后，写入 {@code VectorStore}</li>
 *   <li>语义检索：根据自然语言 query 召回最相关的条文片段，供审核 Agent / 前端 RAG 使用</li>
 *   <li>按文档删除：把同一 {@code documentId} 下的所有 chunk 从向量库一次性清掉</li>
 * </ul>
 *
 * <p>实际写入 / 检索 / 删除操作全部委托给 {@link KnowledgeService}，
 * 本控制器只负责 HTTP 入参绑定与统一响应包装。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 上传一份规范文档并入库：{@code POST /api/knowledge/upload}。
     *
     * <p>请求格式为 multipart/form-data：
     * <ul>
     *   <li>{@code file}：必填，待入库的文档文件（Tika 支持的格式皆可，如 .pdf / .docx / .txt）</li>
     *   <li>{@code category}：可选，文档分类标签，写入 metadata 便于后续 {@link #search} 按分类过滤；
     *       不传则在 Service 层默认填 {@code "general"}</li>
     * </ul>
     *
     * <p>返回的 {@link KnowledgeUploadResponse} 中带有自动生成的 {@code documentId}（UUID）、
     * 切片得到的 {@code chunkCount} 与状态字段，前端可据此向用户展示入库进度。
     *
     * <p>典型场景：审核管理员把新颁布的规范 PDF 上传到系统，后续审核流程的 RAG 检索就能命中它。
     */
    @PostMapping("/upload")
    public ApiResult<KnowledgeUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category) {
        KnowledgeUploadResponse response = knowledgeService.ingestDocument(file, category);
        return ApiResult.ok(response);
    }

    /**
     * 在知识库里做语义检索：{@code POST /api/knowledge/search}。
     *
     * <p>请求体字段：
     * <ul>
     *   <li>{@code query}：自然语言检索文本</li>
     *   <li>{@code topK}：返回前 N 条相似结果，默认 5</li>
     *   <li>{@code category}：可选，限定在指定分类内检索；空 / 空白串视为不做分类过滤</li>
     * </ul>
     *
     * <p>有 category 时走 {@link KnowledgeService#searchWithFilter}（带 metadata filter）；
     * 否则走 {@link KnowledgeService#searchRelevantClauses}（全库相似度）。
     *
     * <p>返回结果按相似度高到低排序，每条包含 chunk 内容、相似度分值与原始 metadata，
     * 前端可直接展示或拼到下游 LLM 的上下文里。
     */
    @PostMapping("/search")
    public ApiResult<List<KnowledgeSearchResult>> search(
            @RequestBody KnowledgeSearchRequest request) {
        List<KnowledgeSearchResult> results;
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            results = knowledgeService.searchWithFilter(
                    request.getQuery(), request.getTopK(), request.getCategory());
        } else {
            results = knowledgeService.searchRelevantClauses(
                    request.getQuery(), request.getTopK());
        }
        return ApiResult.ok(results);
    }

    /**
     * 按 documentId 删除知识库里的全部切片：{@code DELETE /api/knowledge/documents/{documentId}}。
     *
     * <p>路径参数 {@code documentId} 是上传时由后端生成的 UUID（见 {@link #upload} 的返回值），
     * Service 会按 metadata 上的同名字段做精确匹配并物理删除所有相关 chunk。
     *
     * <p>典型场景：规范被新版本替换、或被误传需要回收时的下架操作。删除不可逆。
     */
    @DeleteMapping("/documents/{documentId}")
    public ApiResult<Void> deleteDocument(@PathVariable String documentId) {
        knowledgeService.deleteDocument(documentId);
        return ApiResult.ok(null);
    }
}

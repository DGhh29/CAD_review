package com.luckycat.cadreview.service;

import com.luckycat.cadreview.dto.KnowledgeSearchResult;
import com.luckycat.cadreview.dto.KnowledgeUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库（规范条款向量库）服务。
 *
 * <p>底层 {@link VectorStore} 由
 * {@link com.luckycat.cadreview.config.VectorStoreConfig} 间接装配
 * （实际由 {@code spring-ai-starter-vector-store-pgvector} 自动配置出
 * {@code PgVectorStore}），本类只做 Tika 解析、分块、检索与按 documentId 删除等编排。
 *
 * <p>调用方：
 * <ul>
 *   <li>{@link com.luckycat.cadreview.controller.KnowledgeController}：上传 / 检索 / 删除接口</li>
 *   <li>{@link ChatServiceImpl#buildRagContext}：聊天前的 RAG 检索</li>
 * </ul>
 *
 * <p>失败模式：
 * <ul>
 *   <li>读取 MultipartFile 字节流失败——抛 {@link RuntimeException}</li>
 *   <li>向量库写入 / 检索失败——异常由 Spring AI 抛出，调用方按需兜底</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final VectorStore vectorStore;

    /**
     * 上传一个文档到知识库，自动做 Tika 解析、按 token 分块、写入向量库。
     *
     * <p>分块参数：chunk size = 800 tokens，min chunk chars = 300，保留分隔符。
     * 每个 chunk 都被打上 documentId / fileName / category / uploadTime 元数据，
     * 便于后续按 documentId 整体删除或按 category 过滤检索。
     *
     * @param file     用户上传的文件，Tika 自动识别格式（pdf / docx / md / txt 等）
     * @param category 业务分类，{@code null} 时落 {@code "general"}
     * @return 包含生成的 documentId 与分块数量的应答对象
     * @throws RuntimeException 读取文件输入流失败
     */
    public KnowledgeUploadResponse ingestDocument(MultipartFile file, String category) {
        String documentId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename();
        log.info("Ingesting document: {} (id={})", fileName, documentId);

        InputStreamResource resource;
        try {
            resource = new InputStreamResource(file.getInputStream());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.read();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("fileName", fileName);
        metadata.put("category", category != null ? category : "general");
        metadata.put("uploadTime", LocalDateTime.now().toString());
        docs.forEach(d -> d.getMetadata().putAll(metadata));

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(300)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(docs);

        vectorStore.add(chunks);
        log.info("Ingested {} chunks for document: {}", chunks.size(), fileName);

        return KnowledgeUploadResponse.builder()
                .documentId(documentId)
                .chunkCount(chunks.size())
                .fileName(fileName)
                .status("SUCCESS")
                .build();
    }

    /**
     * 用自然语言问题在向量库做相似度检索，返回 top-K 个分块。
     *
     * <p>{@code score} 字段优先用 metadata 里的 {@code distance} 反推
     * （{@code 1 - distance}）；无 distance 时退化为 {@code 0.0}，方便前端展示。
     *
     * @param query 检索问题
     * @param topK  返回条数上限
     */
    public List<KnowledgeSearchResult> searchRelevantClauses(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
                .map(doc -> KnowledgeSearchResult.builder()
                        .id(doc.getId())
                        .content(doc.getText())
                        .score(doc.getMetadata().containsKey("distance")
                                ? 1.0 - ((Number) doc.getMetadata().get("distance")).doubleValue()
                                : 0.0)
                        .metadata(doc.getMetadata())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 类目过滤版的相似度检索：只在 {@code category} 等于指定值的分块中召回。
     *
     * <p>{@code category} 为空 / 空白时退化为不带过滤的检索；
     * 这样前端"默认所有类目"和"指定单一类目"可以走同一个接口。
     */
    public List<KnowledgeSearchResult> searchWithFilter(String query, int topK, String category) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK);

        if (category != null && !category.isBlank()) {
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            builder.filterExpression(fb.eq("category", category).build());
        }

        List<Document> results = vectorStore.similaritySearch(builder.build());

        return results.stream()
                .map(doc -> KnowledgeSearchResult.builder()
                        .id(doc.getId())
                        .content(doc.getText())
                        .score(doc.getMetadata().containsKey("distance")
                                ? 1.0 - ((Number) doc.getMetadata().get("distance")).doubleValue()
                                : 0.0)
                        .metadata(doc.getMetadata())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 按 documentId 整体删除某个文档的所有分块。
     *
     * <p>用 {@link Filter.Expression} 精准匹配 metadata 中的 documentId，
     * 保证一份文档"上传时是 N 块，删除时也是 N 块"。
     */
    public void deleteDocument(String documentId) {
        Filter.Expression filterExpression = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("documentId"),
                new Filter.Value(documentId)
        );
        vectorStore.delete(filterExpression);
        log.info("Deleted all chunks for documentId={}", documentId);
    }
}

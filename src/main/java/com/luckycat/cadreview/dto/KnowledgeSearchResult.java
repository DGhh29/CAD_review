package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 知识库语义检索结果项。
 *
 * <p>由 {@code KnowledgeService} 从 {@code VectorStore.similaritySearch} 的命中 chunk 转换而来，
 * 一次检索返回一个 List；按相似度得分降序排列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchResult {

    // 命中分块的唯一 ID，对应向量库中的 chunk ID（不是 documentId）；
    // 删除文档需要走 documentId，单条删除则可用此 ID
    private String id;

    // 命中分块的正文文本，即原始文档被切分后的一段；前端用来展示规范条文摘录
    private String content;

    // 相似度得分。具体语义依赖 VectorStore 实现（PgVector 是距离越小越相似还是越大越相似），
    // 前端只用于排序展示，不应该写死阈值判断
    private double score;

    // 分块的元数据：documentId / fileName / category / uploadTime 等
    // 由 KnowledgeService.ingestDocument 时写入，供前端展示来源与做二次过滤
    private Map<String, Object> metadata;
}

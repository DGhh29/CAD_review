package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库（规范条文向量库）语义检索请求体（{@code POST /api/knowledge/search}）。
 *
 * <p>由前端发起，KnowledgeService 用来在 PgVectorStore 中按相似度召回与查询相关的规范片段，
 * ChatService 在回答时也通过类似检索把召回片段拼进 system prompt 做 RAG。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSearchRequest {

    // 查询文本，可以是自然语言问题或关键词；交给 embedding 模型转向量后做相似度检索
    private String query;

    // 返回的相似结果数量上限。默认 5：太少召回不到，太多稀释相关度也会拖慢响应；
    // 用 @Builder.Default 保证 Builder 构造时也能拿到默认值
    @Builder.Default
    private int topK = 5;

    // 分类过滤标签。非空时走 KnowledgeService.searchWithFilter，
    // 在 metadata.category 等于该值的 chunk 范围内召回；空 / 空白串则退化为全库检索
    private String category;
}

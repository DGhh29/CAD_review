package com.luckycat.cadreview.config;

import org.springframework.context.annotation.Configuration;

/**
 * 知识库（RAG）向量存储的占位配置。
 *
 * <p>当前并未在此声明任何 Bean——{@code PgVectorStore} 由 Spring AI 的
 * {@code spring-ai-starter-vector-store-pgvector} 自动装配，
 * 参数全部由 application.yml 的 {@code spring.ai.vectorstore.pgvector.*} 与
 * {@code spring.datasource.*} 提供（HNSW 索引、COSINE_DISTANCE 距离、
 * 维度由 {@code EMBEDDING_DIMENSIONS} 注入，启动期 {@code initialize-schema=true} 自建表）。
 *
 * <p>自动装配出的 VectorStore 被 {@link com.luckycat.cadreview.service.KnowledgeService} 直接注入，
 * 用于规范文档 ingest（TikaDocumentReader → TokenTextSplitter → VectorStore.add）以及
 * Chat 流程中的相似度检索。
 *
 * <p>当未来需要"多向量表 / 多 schema / 自定义 EmbeddingModel 切换"等场景时，
 * 在本类里新增 @Bean 覆盖默认装配即可——保留这个空类是给后续扩展留好挂载点。
 */
@Configuration
public class VectorStoreConfig {
    // PgVectorStore is auto-configured by spring-ai-starter-vector-store-pgvector.
    // Add custom bean definitions here if you need multiple vector tables or schemas.
}

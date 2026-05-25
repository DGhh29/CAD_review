package com.luckycat.cadreview.agent;

/**
 * Evidence chunk 抽取接口，便于单元测试用本地 fake 替代真实 LLM 调用。
 */
public interface EvidenceExtractionClient {

    EvidenceExtractionResult extract(EvidenceSearchTask task, EvidenceChunk chunk);
}

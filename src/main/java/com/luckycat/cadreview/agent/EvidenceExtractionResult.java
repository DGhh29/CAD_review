package com.luckycat.cadreview.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量模型对一个 EvidenceChunk 的抽取结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceExtractionResult {

    private String taskId;
    private String chunkId;
    private boolean relevant;

    @Builder.Default
    private List<ExtractedEvidence> evidence = new ArrayList<>();

    @Builder.Default
    private List<String> stillMissing = new ArrayList<>();
}

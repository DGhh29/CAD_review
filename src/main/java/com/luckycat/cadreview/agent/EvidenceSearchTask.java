package com.luckycat.cadreview.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 针对某个审核任务的原始 IR 补证请求。
 *
 * <p>它只描述“要去 raw_ir 里找什么”，不承担合规判断。后续由 {@link RawIrChunker}
 * 根据这些线索切片，再由 {@link EvidenceExtractorAgent} 批量抽取证据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceSearchTask {

    private String runId;
    private String taskId;
    private String ruleId;
    private String category;

    @Builder.Default
    private List<String> missingEvidence = new ArrayList<>();

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private List<String> layerHints = new ArrayList<>();

    @Builder.Default
    private List<String> entityTypeHints = new ArrayList<>();

    @Builder.Default
    private int maxChunks = 40;
}

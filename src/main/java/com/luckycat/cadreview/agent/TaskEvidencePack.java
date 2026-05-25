package com.luckycat.cadreview.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 定向补证后沉淀给 Reviewer 使用的任务级证据包。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskEvidencePack {

    private String runId;
    private String taskId;
    private String ruleId;
    private int attempt;

    @Builder.Default
    private List<ExtractedEvidence> foundEvidence = new ArrayList<>();

    @Builder.Default
    private List<String> missingEvidence = new ArrayList<>();

    @Builder.Default
    private Quality quality = new Quality();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Quality {
        private int evidenceCount;
        private boolean sourceTraceable;
        private double confidence;
        private String repairStatus;
    }
}

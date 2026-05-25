package com.luckycat.cadreview.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 ReviewTask 的补证执行结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRepairResult {

    private boolean attempted;
    private boolean usefulEvidence;
    private String status;
    private String reason;
    private TaskEvidencePack evidencePack;

    public boolean hasUsefulEvidence() {
        return usefulEvidence;
    }
}

package com.luckycat.cadreview.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标计算的目标筛选条件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CadMetricTarget {

    @Builder.Default
    private List<String> entityTypes = new ArrayList<>();

    @Builder.Default
    private List<String> layerHints = new ArrayList<>();

    @Builder.Default
    private List<String> textHints = new ArrayList<>();

    @Builder.Default
    private List<String> semanticHints = new ArrayList<>();

    @Builder.Default
    private List<String> excludeHints = new ArrayList<>();

    @Builder.Default
    private Integer maxEvidence = 8;

    @Builder.Default
    private Double tolerance = 1.0d;
}

package com.luckycat.cadreview.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标结果对应的可追溯 CAD 证据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRef {

    private String source;
    private String entityId;
    private String type;
    private String layer;
    private String text;

    @Builder.Default
    private List<Double> point = new ArrayList<>();

    @Builder.Default
    private List<Double> bbox = new ArrayList<>();
}

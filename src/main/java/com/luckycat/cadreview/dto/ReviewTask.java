package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTask {

    @NotBlank
    private String taskId;

    @NotBlank
    private String checkItem;

    @Builder.Default
    private List<String> ruleIds = new ArrayList<>();

    @Builder.Default
    private List<String> entityIds = new ArrayList<>();

    @Builder.Default
    private String areaId = "UNKNOWN";

    @Builder.Default
    private List<String> layerNames = new ArrayList<>();

    @NotNull
    @Builder.Default
    private RiskLevel priority = RiskLevel.MEDIUM;
}

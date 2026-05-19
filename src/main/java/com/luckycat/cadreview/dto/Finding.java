package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.dto.enums.Verdict;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
public class Finding {
    private Verdict verdict;
    private RiskLevel riskLevel;
    private String reason;
    private String clauseId;

    @Builder.Default
    private List<String> evidenceEntityIds = new ArrayList<>();

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double confidence;
}

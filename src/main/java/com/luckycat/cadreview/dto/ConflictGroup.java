package com.luckycat.cadreview.dto;

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
public class ConflictGroup {

    private String areaId;
    private String clauseId;

    @Builder.Default
    private List<Finding> conflictingFindings = new ArrayList<>();

    private Finding resolvedFinding;
}

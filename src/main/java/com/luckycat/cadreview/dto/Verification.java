package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Verdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Verification {
    private String status;
    private String verifiedBy;
    private String comment;
    private Verdict verifiedVerdict;
    private String verifiedReason;
}

package com.luckycat.cadreview.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRule {

    @NotBlank
    private String id;

    @NotBlank
    private String clauseId;

    @NotBlank
    private String title;

    @NotBlank
    private String scope;

    @NotBlank
    private String promptFragment;

    @NotBlank
    private String version;

    @Builder.Default
    private boolean enabled = true;
}

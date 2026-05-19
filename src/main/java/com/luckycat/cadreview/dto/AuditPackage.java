package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AuditPackage {
    private Provider provider;

    @NotNull
    private ProjectInfo projectInfo;

    @NotBlank
    private String checkItem;

    @NotNull
    private Map<String, Object> extractedParameters;

    @NotNull
    private Clause clause;

    @NotNull
    private Evidence evidence;

    @Data
    public static class ProjectInfo {
        private String drawingType;
        private String discipline;
    }

    @Data
    public static class Clause {
        private String clauseId;
        private String summary;
    }

    @Data
    public static class Evidence {
        private List<String> entityIds;
        private String layer;
        private List<Double> worldBounds;
    }
}

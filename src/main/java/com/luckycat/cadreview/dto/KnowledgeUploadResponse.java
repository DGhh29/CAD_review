package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadResponse {
    private String documentId;
    private int chunkCount;
    private String fileName;
    private String status;
}

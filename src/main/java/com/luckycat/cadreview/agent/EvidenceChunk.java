package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 raw_ir 中按任务线索切出的一小批候选数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceChunk {

    private String chunkId;
    private String chunkType;
    private String source;
    private String reason;

    @Builder.Default
    private List<JsonNode> items = new ArrayList<>();
}

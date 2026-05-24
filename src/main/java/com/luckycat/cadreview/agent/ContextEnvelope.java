package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 喂给模型前的统一输入信封。
 *
 * <p>模型真正需要看的业务上下文放在 context；budget/omitted 告诉模型
 * 当前输入是否被压缩过，以及哪些信息没有进入本轮判断。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextEnvelope {
    private String stage;
    private String taskId;
    private ContextBudget budget;
    private JsonNode context;
    private JsonNode quality;

    @Builder.Default
    private List<String> omitted = new ArrayList<>();
}

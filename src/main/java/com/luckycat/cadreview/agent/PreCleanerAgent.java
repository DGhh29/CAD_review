package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 任务级前置清洗 Agent。
 *
 * <p>v1 使用确定性预算清洗，不额外消耗模型 token。只有当上下文仍超长时，
 * envelope.budget 会标记 SPLIT_AND_REDUCE / PENDING_REVIEW，交给 Reviewer 保守处理。
 */
@Component
@RequiredArgsConstructor
public class PreCleanerAgent {

    private final ObjectMapper objectMapper;
    private final ContextBudgetService contextBudgetService;

    public JsonNode cleanForTask(String taskId, JsonNode taskContext) {
        ContextEnvelope envelope = contextBudgetService.wrap(
                AgentRole.PRE_CLEANER,
                "TASK_PRE_CLEAN",
                taskId,
                taskContext);
        return objectMapper.valueToTree(envelope);
    }
}

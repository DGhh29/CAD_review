package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetServiceTest {

    @Test
    void shouldShrinkLargeArraysAndRecordDroppedCounts() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentProperties properties = new AgentProperties();
        properties.getContextBudget().setReviewerMaxChars(2000);
        properties.getContextBudget().setFirstPassArrayItems(10);
        properties.getContextBudget().setSecondPassArrayItems(5);
        properties.getContextBudget().setFinalPassArrayItems(2);
        ContextBudgetService service = new ContextBudgetService(objectMapper, properties);

        ObjectNode context = objectMapper.createObjectNode();
        ArrayNode entities = context.putArray("entities");
        for (int i = 0; i < 100; i++) {
            entities.addObject()
                    .put("id", "E-" + i)
                    .put("text", "这是一段用于撑大上下文的测试文字".repeat(20));
        }

        ContextEnvelope envelope = service.wrap(AgentRole.REVIEWER, "REVIEWER", "TASK-1", context);

        assertThat(envelope.getBudget().isOverflow()).isTrue();
        assertThat(envelope.getBudget().getDroppedCounts()).isNotEmpty();
        assertThat(envelope.getContext().path("entities").size()).isLessThan(100);
    }
}

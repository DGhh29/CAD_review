package com.luckycat.cadreview.agent;

import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest {

    @Test
    void shouldSortAndLimitTasksDeterministically() {
        List<ReviewTask> tasks = List.of(
                task("low", RiskLevel.LOW, "Z_RULE", "L3"),
                task("high-b", RiskLevel.HIGH, "B_RULE", "L2"),
                task("high-a", RiskLevel.HIGH, "A_RULE", "L1"),
                task("medium", RiskLevel.MEDIUM, "M_RULE", "L1")
        );

        AgentOrchestrator.DispatchPlan plan = AgentOrchestrator.limitTasks(tasks, 2);

        assertThat(plan.executableTasks())
                .extracting(ReviewTask::getTaskId)
                .containsExactly("high-a", "high-b");
        assertThat(plan.skippedTasks())
                .extracting(ReviewTask::getTaskId)
                .containsExactly("medium", "low");
        assertThat(plan.skippedRuleIds()).containsExactly("M_RULE", "Z_RULE");
    }

    private ReviewTask task(String taskId, RiskLevel priority, String ruleId, String layerName) {
        return ReviewTask.builder()
                .taskId(taskId)
                .checkItem(taskId)
                .priority(priority)
                .ruleIds(List.of(ruleId))
                .layerNames(List.of(layerName))
                .areaId("A")
                .build();
    }
}

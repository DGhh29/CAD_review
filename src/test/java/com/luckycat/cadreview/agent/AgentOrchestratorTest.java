package com.luckycat.cadreview.agent;

import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentOrchestrator} 编排器单元测试。
 *
 * <p>这里聚焦在编排器的"任务排序 + 数量截断"这个纯函数式分支
 * （{@link AgentOrchestrator#limitTasks(List, int)}），它决定了哪些任务会真正被
 * 发给 Reviewer、哪些会被记成 skipped 出现在最终报告里。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>多优先级混合输入下，是否按 HIGH → MEDIUM → LOW 严格排序</li>
 *   <li>同优先级时是否按 ruleId / layerName 等次级 key 给出确定性顺序</li>
 *   <li>maxReviewTasks 截断行为，以及被截断的任务能否正确归入 skippedTasks / skippedRuleIds</li>
 * </ul>
 *
 * <p>不验证完整的 executeReview 链路（解析 / Dispatcher / Reviewer / Summarizer 各阶段
 * 已经分别有自己的单元测试覆盖）。
 */
class AgentOrchestratorTest {

    /**
     * 验证排序与截断的确定性：
     * 输入故意打乱顺序、并构造两个 HIGH 优先级任务来检验同优先级下的 tie-break，
     * 期望执行集合是 [high-a, high-b]，被跳过集合是 [medium, low]，
     * 且 skippedRuleIds 按跳过任务的顺序去重输出。
     */
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

    @Test
    void shouldTreatNonPositiveLimitAsUnlimited() {
        List<ReviewTask> tasks = List.of(
                task("low", RiskLevel.LOW, "Z_RULE", "L3"),
                task("high", RiskLevel.HIGH, "A_RULE", "L1")
        );

        AgentOrchestrator.DispatchPlan plan = AgentOrchestrator.limitTasks(tasks, 0);

        assertThat(plan.executableTasks())
                .extracting(ReviewTask::getTaskId)
                .containsExactly("high", "low");
        assertThat(plan.skippedTasks()).isEmpty();
    }

    /** 构造一个最小可比较的 ReviewTask，仅设置排序需要用到的字段。 */
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

package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.Finding;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.enums.RiskLevel;
import com.luckycat.cadreview.dto.enums.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SummarizerAgent} 单元测试。
 *
 * <p>Summarizer 是流水线的最后一棒：把 Reviewer 阶段产出的所有 finding 折叠成一份
 * {@link ReviewReport}，决定 overallVerdict、识别冲突、统计覆盖率、标记 partial。
 *
 * <p>本类聚焦在两个关键的<b>纯逻辑</b>分支（关闭了二次 LLM 验证以避免依赖外部模型）：
 * <ul>
 *   <li>同一图元上同时存在 PASS 与 FAIL 时，应识别为冲突并把整体结论降级为 PENDING_REVIEW</li>
 *   <li>有任务被跳过（数量超限或调度失败）时，应把 partial 标志置为 true，
 *       并把跳过的 task / rule ID 透传到 coverage 字段里</li>
 * </ul>
 */
class SummarizerAgentTest {

    /**
     * 验证冲突检测：同一 evidenceEntityId 上既有 PASS 又有 FAIL，
     * 整体结论必须是 PENDING_REVIEW（不能强行二选一），
     * conflicts 列表里要记录这一冲突；coverage 仍按成功任务数 1 统计；
     * 且因为没有失败/跳过任务，partial 标志保持 false。
     */
    @Test
    void shouldDetectConflictsAndReturnPendingReview() {
        // verificationEnabled=false 让 Summarizer 走纯本地逻辑分支，无需 LLM
        SummarizerAgent summarizer = new SummarizerAgent(
                null, null, buildProperties(), new ObjectMapper(), null, null);
        ReviewTask task = task("task-1", RiskLevel.HIGH, "RULE-1");
        Finding pass = Finding.builder()
                .verdict(Verdict.PASS)
                .riskLevel(RiskLevel.LOW)
                .clauseId("C-1")
                .ruleId("RULE-1")
                .areaId("AREA-1")
                .evidenceEntityIds(List.of("E-1"))
                .confidence(0.9d)
                .build();
        // 同一证据 E-1 给出相反结论，应被识别为冲突
        Finding fail = Finding.builder()
                .verdict(Verdict.FAIL)
                .riskLevel(RiskLevel.HIGH)
                .clauseId("C-1")
                .ruleId("RULE-1")
                .areaId("AREA-1")
                .evidenceText("同一图元存在相反结论")
                .evidenceEntityIds(List.of("E-1"))
                .confidence(0.8d)
                .build();

        ReviewReport report = summarizer.summarize(ReviewSummaryInput.builder()
                .findings(List.of(pass, fail))
                .succeededTasks(List.of(task))
                .durationMs(123)
                .build());

        assertThat(report.getOverallVerdict()).isEqualTo(Verdict.PENDING_REVIEW);
        assertThat(report.getConflicts()).hasSize(1);
        assertThat(report.getCoverage().getSucceededTasks()).isEqualTo(1);
        assertThat(report.isPartial()).isFalse();
    }

    /**
     * 验证 partial 降级路径：当存在 skippedTasks（被任务上限截断或调度失败）时，
     * 即便没有任何 finding，整体结论也应是 PENDING_REVIEW，
     * partial=true，coverage 里能正确反映 totalTasks=2 以及被跳过的 task/rule id。
     */
    @Test
    void shouldMarkPartialWhenTasksAreSkipped() {
        SummarizerAgent summarizer = new SummarizerAgent(
                null, null, buildProperties(), new ObjectMapper(), null, null);

        ReviewReport report = summarizer.summarize(ReviewSummaryInput.builder()
                .findings(List.of())
                .succeededTasks(List.of(task("task-1", RiskLevel.LOW, "RULE-1")))
                .skippedTasks(List.of(task("task-2", RiskLevel.HIGH, "RULE-2")))
                .durationMs(100)
                .build());

        assertThat(report.getOverallVerdict()).isEqualTo(Verdict.PENDING_REVIEW);
        assertThat(report.isPartial()).isTrue();
        assertThat(report.getCoverage().getTotalTasks()).isEqualTo(2);
        assertThat(report.getCoverage().getSkippedTaskIds()).containsExactly("task-2");
        assertThat(report.getCoverage().getSkippedRuleIds()).containsExactly("RULE-2");
    }

    /**
     * 关闭 verificationEnabled，避免 Summarizer 走二次 LLM 验证分支
     * （那条路径依赖 reviewClient，本测试构造时传入 null）。
     */
    private AgentProperties buildProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSummarizer().setVerificationEnabled(false);
        return properties;
    }

    /** 构造一个最小化的任务样本，只填汇总用得到的字段。 */
    private ReviewTask task(String taskId, RiskLevel priority, String ruleId) {
        return ReviewTask.builder()
                .taskId(taskId)
                .checkItem(taskId)
                .priority(priority)
                .ruleIds(List.of(ruleId))
                .areaId("AREA-1")
                .build();
    }
}

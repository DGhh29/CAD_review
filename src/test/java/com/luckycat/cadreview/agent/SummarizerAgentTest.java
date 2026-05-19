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

class SummarizerAgentTest {

    @Test
    void shouldDetectConflictsAndReturnPendingReview() {
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

    private AgentProperties buildProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSummarizer().setVerificationEnabled(false);
        return properties;
    }

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

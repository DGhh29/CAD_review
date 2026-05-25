package com.luckycat.cadreview.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.agent.ContextBudget;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewRunStatus;
import com.luckycat.cadreview.dto.ReviewRunSummary;
import com.luckycat.cadreview.dto.ReviewTask;
import com.luckycat.cadreview.dto.ReviewTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 异步审图运行记录的轻量 JDBC Repository。
 *
 * <p>v1 不引入 JPA/Flyway，启动时自建三张小表：run、task、context_event。
 * 原始 CAD 解析 JSON 只作为 artifact 存库，模型调用不直接读取全量 artifact。
 */
@Repository
@RequiredArgsConstructor
public class ReviewRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void initializeSchema() {
        jdbcTemplate.execute("""
                create table if not exists cad_review_runs (
                    run_id varchar(64) primary key,
                    status varchar(32) not null,
                    file_name text,
                    rule_set text,
                    reason text,
                    raw_ir_json text,
                    clean_context_json text,
                    report_json text,
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp,
                    completed_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists cad_review_run_tasks (
                    id bigserial primary key,
                    run_id varchar(64) not null,
                    task_id varchar(128) not null,
                    status varchar(32) not null,
                    task_json text not null,
                    reason text,
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp,
                    unique(run_id, task_id)
                )
                """);
        jdbcTemplate.execute("""
                create table if not exists cad_review_context_events (
                    id bigserial primary key,
                    run_id varchar(64) not null,
                    task_id varchar(128),
                    stage varchar(64) not null,
                    budget_json text not null,
                    created_at timestamp not null default current_timestamp
                )
                """);
    }

    public void createRun(String runId, String fileName, String ruleSet) {
        jdbcTemplate.update("""
                insert into cad_review_runs(run_id, status, file_name, rule_set)
                values (?, ?, ?, ?)
                """, runId, ReviewRunStatus.UPLOADED.name(), fileName, ruleSet);
    }

    public void updateStatus(String runId, ReviewRunStatus status, String reason) {
        jdbcTemplate.update("""
                update cad_review_runs
                set status = ?, reason = ?, updated_at = current_timestamp,
                    completed_at = case when ? in ('COMPLETED','PARTIAL','FAILED') then current_timestamp else completed_at end
                where run_id = ?
                """, status.name(), reason, status.name(), runId);
    }

    public void saveRawIr(String runId, JsonNode rawIr) {
        jdbcTemplate.update("""
                update cad_review_runs
                set raw_ir_json = ?, updated_at = current_timestamp
                where run_id = ?
                """, toJson(rawIr), runId);
    }

    public void saveCleanContext(String runId, JsonNode cleanContext) {
        jdbcTemplate.update("""
                update cad_review_runs
                set clean_context_json = ?, updated_at = current_timestamp
                where run_id = ?
                """, toJson(cleanContext), runId);
    }

    public void saveReport(String runId, ReviewReport report) {
        jdbcTemplate.update("""
                update cad_review_runs
                set report_json = ?, updated_at = current_timestamp
                where run_id = ?
                """, toJson(report), runId);
    }

    public void upsertTask(String runId, ReviewTask task, ReviewTaskStatus status, String reason) {
        jdbcTemplate.update("""
                insert into cad_review_run_tasks(run_id, task_id, status, task_json, reason)
                values (?, ?, ?, ?, ?)
                on conflict (run_id, task_id)
                do update set status = excluded.status,
                              task_json = excluded.task_json,
                              reason = excluded.reason,
                              updated_at = current_timestamp
                """, runId, task.getTaskId(), status.name(), toJson(task), reason);
    }

    public void saveContextBudget(String runId, String taskId, String stage, ContextBudget budget) {
        jdbcTemplate.update("""
                insert into cad_review_context_events(run_id, task_id, stage, budget_json)
                values (?, ?, ?, ?)
                """, runId, taskId, stage, toJson(budget));
    }

    public Optional<ReviewRunSummary> findSummary(String runId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    select r.run_id, r.status, r.file_name, r.rule_set, r.reason,
                           r.created_at, r.updated_at, r.completed_at,
                           count(t.id) as total_tasks,
                           sum(case when t.status = 'SUCCEEDED' then 1 else 0 end) as succeeded_tasks,
                           sum(case when t.status = 'FAILED' then 1 else 0 end) as failed_tasks,
                           sum(case when t.status = 'SKIPPED' then 1 else 0 end) as skipped_tasks
                    from cad_review_runs r
                    left join cad_review_run_tasks t on r.run_id = t.run_id
                    where r.run_id = ?
                    group by r.run_id, r.status, r.file_name, r.rule_set, r.reason,
                             r.created_at, r.updated_at, r.completed_at
                    """, summaryMapper(), runId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<ReviewRunSummary> listSummaries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                select r.run_id, r.status, r.file_name, r.rule_set, r.reason,
                       r.created_at, r.updated_at, r.completed_at,
                       count(t.id) as total_tasks,
                       sum(case when t.status = 'SUCCEEDED' then 1 else 0 end) as succeeded_tasks,
                       sum(case when t.status = 'FAILED' then 1 else 0 end) as failed_tasks,
                       sum(case when t.status = 'SKIPPED' then 1 else 0 end) as skipped_tasks
                from cad_review_runs r
                left join cad_review_run_tasks t on r.run_id = t.run_id
                group by r.run_id, r.status, r.file_name, r.rule_set, r.reason,
                         r.created_at, r.updated_at, r.completed_at
                order by r.updated_at desc
                limit ?
                """, summaryMapper(), safeLimit);
    }

    public Optional<ReviewReport> findReport(String runId) {
        try {
            String json = jdbcTemplate.queryForObject(
                    "select report_json from cad_review_runs where run_id = ?",
                    String.class,
                    runId);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ReviewReport.class));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read review report", ex);
        }
    }

    private RowMapper<ReviewRunSummary> summaryMapper() {
        return (rs, rowNum) -> ReviewRunSummary.builder()
                .runId(rs.getString("run_id"))
                .status(ReviewRunStatus.valueOf(rs.getString("status")))
                .fileName(rs.getString("file_name"))
                .ruleSet(rs.getString("rule_set"))
                .reason(rs.getString("reason"))
                .createdAt(toOffsetDateTime(rs, "created_at"))
                .updatedAt(toOffsetDateTime(rs, "updated_at"))
                .completedAt(toOffsetDateTime(rs, "completed_at"))
                .totalTasks(rs.getInt("total_tasks"))
                .succeededTasks(rs.getInt("succeeded_tasks"))
                .failedTasks(rs.getInt("failed_tasks"))
                .skippedTasks(rs.getInt("skipped_tasks"))
                .build();
    }

    private OffsetDateTime toOffsetDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize review run artifact", ex);
        }
    }
}

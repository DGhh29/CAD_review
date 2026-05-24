package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.RiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个评审子任务——把"整图审核"切成可并行执行的最小工作单元。
 *
 * <p>由 DispatcherAgent 产出（输入：IR 摘要 + 启用规则集，让 LLM 拆出一组任务），
 * 经 {@code DispatcherAgent.normalizeTask} 规范化后交给 ReviewerAgent 并行执行。
 *
 * <p>规范化阶段会做的事（决定字段语义）：
 * <ul>
 *   <li>{@code ruleIds} 必须非空，且每个 ID 都要在本次启用的规则白名单内（防 LLM 幻觉）；
 *       用 {@link java.util.LinkedHashSet} 去重保留 LLM 给出的相对顺序。</li>
 *   <li>{@code taskId} 缺失时补 "TASK-NNN"；{@code checkItem} 缺失时取首条规则的 title 兜底；
 *       {@code areaId} 缺失时给 "UNKNOWN"。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTask {

    // 任务唯一 ID（同一份 ReviewReport 内不重复）；缺失时由 Dispatcher 补成 "TASK-NNN"
    @NotBlank
    private String taskId;

    // 该任务要审核的具体审查项描述，给 Reviewer 的 LLM 看；缺失时取首条规则的 title 兜底
    @NotBlank
    private String checkItem;

    // 审图类别，例如 fire / parking / planning_indicator / site_plan / drawing_depth。
    // 由 Dispatcher 生成，缺失时按规则标题和图层线索兜底为 general。
    @Builder.Default
    private String category = "general";

    // 本任务要校验的规则 ID 集合，必须非空且全部命中规则白名单；
    // ReviewerAgent 进一步要求 Finding.ruleId 必须落在本列表内
    @Builder.Default
    private List<String> ruleIds = new ArrayList<>();

    // 该任务关注的 CAD 实体 ID 列表，用于 IrViewService 切出最小相关 IR 喂给 Reviewer，
    // 避免把整图原样塞给 LLM 浪费 token
    @Builder.Default
    private List<String> entityIds = new ArrayList<>();

    // 区域 ID，例如 "AREA_LOBBY"；缺失时强制为 "UNKNOWN"。
    // SummarizerAgent 以 (areaId + clauseId) 为冲突检测主键，跨区域的同条款不算冲突
    @Builder.Default
    private String areaId = "UNKNOWN";

    // 涉及的图层名列表；用来限定 IR 切片范围，并被 Reviewer 在 Finding.layerNames 缺省时继承下去
    @Builder.Default
    private List<String> layerNames = new ArrayList<>();

    // 该任务需要读取的清洗证据分组，例如 fire、parking、site_boundary、dimensions。
    // TaskContextBuilder 会据此从 review_context.evidence_groups 中取最小证据包。
    @Builder.Default
    private List<String> evidenceGroups = new ArrayList<>();

    // 任务级上下文处理策略，默认走自动预算裁剪；超长时可由 Dispatcher 明确要求分片或人工复核。
    @Builder.Default
    private String contextPolicy = "AUTO_BUDGET";

    // 任务优先级（HIGH / MEDIUM / LOW）。@NotNull + @Builder.Default=MEDIUM：
    // Dispatcher 排序优先按此降序，让重要规则先跑；同优先级再按其他维度做稳定排序
    @NotNull
    @Builder.Default
    private RiskLevel priority = RiskLevel.MEDIUM;
}

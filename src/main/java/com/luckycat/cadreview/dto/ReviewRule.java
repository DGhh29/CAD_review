package com.luckycat.cadreview.dto;

import jakarta.validation.constraints.NotBlank;
import com.luckycat.cadreview.metrics.CadMetricRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评审规则定义——配置驱动的规则元数据。
 *
 * <p>来源：{@code application.yml} 中 {@code cad-review.agent.rules} 列表，
 * 由 {@code AgentProperties} 在启动时加载，并通过 {@code AgentProperties.toRuleMap()}
 * 转成"id → ReviewRule"字典供 DispatcherAgent / ReviewerAgent 在运行期反查。
 *
 * <p>所有 String 字段都加 {@code @NotBlank} 是因为缺一不可：
 * id 用于白名单校验、clauseId / version 写进 Finding 留痕、scope 决定该规则适用什么图纸 /
 * 部位（DispatcherAgent 拆任务的依据）、promptFragment 是直接拼到 Reviewer prompt 里的核心约束文本。
 * 任意一项空白都会让链路中下游某一步失去意义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRule {

    // 规则唯一 ID（业务自定义，建议短而稳定，如 "FIRE_DOOR_WIDTH"）；
    // ReviewTask.ruleIds、Finding.ruleId 都引用该值
    @NotBlank
    private String id;

    // 关联的规范条款 ID（如 "GB50016-3.4.5"）；Reviewer 端会回填进 Finding.clauseId
    @NotBlank
    private String clauseId;

    // 规则的可读标题。当 DispatcherAgent 拆出的任务缺 checkItem 时，会拿首条规则的 title 兜底
    @NotBlank
    private String title;

    // 规则适用范围描述，例如"建筑平面图 / 防火门"。
    // 给 Dispatcher 的 LLM 提示词使用，决定本规则在当前图纸里要不要派任务
    @NotBlank
    private String scope;

    // 直接拼进 Reviewer system prompt 的核心审核约束文本（说人话的判定条件）；
    // LLM 按此判断 PASS / FAIL
    @NotBlank
    private String promptFragment;

    // 规则版本号；写进 Finding.ruleVersion 便于报告留痕，规则升级后旧报告还能追到当时的规则
    @NotBlank
    private String version;

    // 规则是否启用。@Builder.Default=true 让"配了规则就默认开"，关闭时显式置 false；
    // AgentOrchestrator 在产生 ruleMap 前会过滤掉 enabled=false 的项
    @Builder.Default
    private boolean enabled = true;

    // 可选：规则显式声明的确定性指标请求。未配置时由 CadGeometryMetricsService 按规则文本自动推断。
    @Builder.Default
    private List<CadMetricRequest> metricRequests = new ArrayList<>();

    // 可选：该规则完成审核必须具备的证据项，用于 PENDING_REVIEW 后定向补证。
    @Builder.Default
    private List<String> requiredEvidence = new ArrayList<>();

    // 可选：补证搜索线索。配置后 RawIrChunker 会优先按这些关键词、图层和图元类型切片。
    @Builder.Default
    private SearchHints searchHints = new SearchHints();

    // 可选：该规则的补证策略。
    @Builder.Default
    private RepairPolicy repairPolicy = new RepairPolicy();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchHints {
        @Builder.Default
        private List<String> keywords = new ArrayList<>();

        @Builder.Default
        private List<String> layers = new ArrayList<>();

        @Builder.Default
        private List<String> entityTypes = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepairPolicy {
        @Builder.Default
        private boolean enabled = true;
        private int maxChunks;

        @Builder.Default
        private List<String> preferredChunkTypes = new ArrayList<>();
    }
}

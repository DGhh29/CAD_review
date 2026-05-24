package com.luckycat.cadreview.config;

import com.luckycat.cadreview.dto.ReviewRule;
import com.luckycat.cadreview.dto.enums.Provider;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多 Agent 评审编排的全局配置，对应 application.yml 的 {@code cad-review.agent.*} 段。
 *
 * <p>这是整个评审系统的"调度面板"——所有 Agent
 * （{@link com.luckycat.cadreview.agent.AgentOrchestrator}、
 * {@link com.luckycat.cadreview.agent.DispatcherAgent}、
 * {@link com.luckycat.cadreview.agent.ReviewerAgent}、
 * {@link com.luckycat.cadreview.agent.SummarizerAgent}）以及
 * {@link AgentExecutorConfig} 创建的 reviewerTaskExecutor 线程池都会注入本类。
 *
 * <p>同时维护一份完整的审核规则清单（{@link #rules}）：
 * 启动期会做唯一性校验（@PostConstruct），运行期通过 {@link #selectRules(String)}
 * 按前端传入的 ruleSet 过滤出本次要跑的规则。
 *
 * <p>核心时间预算关系（重要）：
 * <pre>
 * totalTimeoutSeconds  =  dispatcher.timeoutSeconds
 *                       + reviewer.timeoutSeconds (× 各任务串行收割时间)
 *                       + summarizer.reserveSeconds
 * </pre>
 * 调整任一字段都要同步审视其他字段，否则会出现"Reviewer 还没跑完 Summarizer 就没时间收尾"的情况。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "cad-review.agent")
public class AgentProperties {

    /** 单次评审最多执行的 ReviewTask 数量；超出的任务会被 Orchestrator 标记为 skipped 不真正执行。
     *  调小：降低 LLM 调用量与成本，但可能漏审；
     *  调大：覆盖更全，但可能导致 Reviewer 线程池排队、总超时被打满。 */
    private int maxReviewTasks = 20;

    /** 切片给 Reviewer 的 IR 实体数量上限（IrViewService.slice 使用），
     *  防止单个任务的 prompt 把上下文窗口撑爆。 */
    private int maxReviewEntities = 800;

    /** 单次评审的总超时预算（秒），由 Dispatcher / Reviewer / Summarizer 三阶段共同消耗，
     *  Orchestrator 会按此值算出 deadline 并在各阶段动态压缩剩余预算。 */
    private int totalTimeoutSeconds = 600;

    /** Dispatcher 多轮调度的最大轮次。
     *  每轮 Dispatcher 可决定继续派 Reviewer 或进入 Summarizer，超过本值后强制收口。 */
    private int maxDispatchRounds = 5;

    /** 异步审图运行器线程池配置。 */
    @Valid
    private Runner runner = new Runner();

    /** 所有模型调用前的上下文预算配置。 */
    @Valid
    private ContextBudgetConfig contextBudget = new ContextBudgetConfig();

    /** 未锚定证据（既无 entityId 又无 boundingBox）比例超过此阈值时，
     *  Summarizer 会把整体 verdict 强制降级为 PENDING_REVIEW；
     *  默认 0.5 表示"过半 finding 都没有几何锚点就视为不可信"。 */
    private double unanchoredPendingThreshold = 0.5d;

    /** Dispatcher 阶段的子配置（调用 LLM 拆任务）。 */
    @Valid
    private Dispatcher dispatcher = new Dispatcher();

    /** Reviewer 阶段的子配置（线程池 + 单任务超时）。 */
    @Valid
    private Reviewer reviewer = new Reviewer();

    /** Summarizer 阶段的子配置（汇总 + 可选二次验证）。 */
    @Valid
    private Summarizer summarizer = new Summarizer();

    /** 全部可用的审核规则清单，由 application.yml 的 cad-review.agent.rules 列表静态配置；
     *  运行期通过 {@link #selectRules} 按需筛选。 */
    @Valid
    private List<ReviewRule> rules = new ArrayList<>();

    /**
     * 启动期校验规则清单：
     * <ul>
     *   <li>同一 (id, version) 组合不允许重复——避免规则被无意复制后引擎按两条独立规则执行；</li>
     *   <li>所有 enabled=true 的规则 id 必须唯一——Orchestrator 会按 id 建立 ruleMap，
     *       同一 id 出现多版本启用会导致 ruleMap 内的版本不可预测。</li>
     * </ul>
     * 任一约束失败直接抛出 IllegalArgumentException，让应用启动失败而不是带病运行。
     */
    @PostConstruct
    public void validateRules() {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        Map<String, Long> duplicates = rules.stream()
                .filter(rule -> rule != null && rule.getId() != null && rule.getVersion() != null)
                .collect(Collectors.groupingBy(rule -> rule.getId() + "@" + rule.getVersion(), LinkedHashMap::new, Collectors.counting()));
        List<String> repeated = duplicates.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!repeated.isEmpty()) {
            throw new IllegalArgumentException("Duplicate review rules found: " + repeated);
        }

        Map<String, Long> enabledRuleIds = rules.stream()
                .filter(rule -> rule != null && rule.isEnabled())
                .filter(rule -> rule.getId() != null)
                .collect(Collectors.groupingBy(ReviewRule::getId, LinkedHashMap::new, Collectors.counting()));
        List<String> repeatedIds = enabledRuleIds.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!repeatedIds.isEmpty()) {
            throw new IllegalArgumentException("Enabled review rule ids must be unique: " + repeatedIds);
        }

    }

    /**
     * 根据前端传入的 ruleSet 过滤出本次要执行的规则集合。
     *
     * <p>过滤规则：
     * <ul>
     *   <li>ruleSet 为 null / 空串 / "all" → 返回全部 enabled=true 的规则；</li>
     *   <li>ruleSet 为逗号/分号/空白分隔的 id 列表 → 仅返回交集；</li>
     *   <li>交集为空时直接抛错，避免 Dispatcher 拿到空规则后去调 LLM 浪费 token。</li>
     * </ul>
     * 返回结果按 id 字典序排序，保证同样输入下多次执行的 prompt 完全一致（利于缓存与回放）。
     */
    public List<ReviewRule> selectRules(String ruleSet) {
        Set<String> requested = parseRuleSet(ruleSet);
        List<ReviewRule> enabledRules = rules.stream()
                .filter(rule -> rule != null && rule.isEnabled())
                .filter(rule -> rule.getId() != null)
                .sorted(Comparator.comparing(ReviewRule::getId))
                .toList();
        if (requested.isEmpty()) {
            return enabledRules;
        }
        List<ReviewRule> selected = enabledRules.stream()
                .filter(rule -> requested.contains(rule.getId()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No matching review rules found for ruleSet: " + ruleSet);
        }
        return selected;
    }

    /**
     * 把规则列表转成按 id 索引的 Map，供 Orchestrator/Reviewer 在运行期按 ruleId 反查规则元数据。
     * 因调用方已经做过 selectRules 过滤，这里只兜底防御性检查，遇到重复 id 直接抛错。
     */
    public Map<String, ReviewRule> ruleMap(List<ReviewRule> selectedRules) {
        Map<String, ReviewRule> map = new LinkedHashMap<>();
        for (ReviewRule rule : selectedRules) {
            if (map.containsKey(rule.getId())) {
                throw new IllegalArgumentException("Duplicate rule id in selected rules: " + rule.getId());
            }
            map.put(rule.getId(), rule);
        }
        return map;
    }

    /**
     * 把前端传来的字符串 ruleSet 拆成有序去重的 id 集合。
     * 支持逗号/分号/空白混排，如 "FIRE_ZONE_AREA_001, EVAC_WIDTH_001"。
     */
    private Set<String> parseRuleSet(String ruleSet) {
        if (ruleSet == null || ruleSet.isBlank() || "all".equalsIgnoreCase(ruleSet.trim())) {
            return Set.of();
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String token : ruleSet.split("[,;\\s]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                requested.add(trimmed);
            }
        }
        return requested;
    }

    /**
     * Dispatcher（任务分派 Agent）配置。
     * Dispatcher 调用一次 LLM 把 IR 摘要 + 规则集拆成多个细粒度 ReviewTask。
     */
    @Data
    public static class Dispatcher {
        /** Dispatcher 单次调用的超时（秒）。Orchestrator 会取本值与剩余 deadline 的较小者，
         *  剩余 deadline 不足 5 秒时直接放弃调用，避免 LLM 半路被打断造成空转计费。 */
        private int timeoutSeconds = 300;

        /** Dispatcher 单次任务的最大尝试次数。注意 StructuredOutputSupport 的循环条件是
         *  attempt &lt;= maxAttempts，所以 maxAttempts=1 实际允许 2 次尝试（首发 + 1 次重试）。
         *  Dispatcher 失败基本意味着 LLM 输出格式不合规，重试通常无意义，默认值 1 已足够。 */
        private int maxAttempts = 1;
    }

    /**
     * Reviewer（具体审核 Agent）配置，同时控制并行执行的线程池规模。
     * 字段被 {@link AgentExecutorConfig#reviewerTaskExecutor()} 直接消费用于线程池建模。
     */
    @Data
    public static class Reviewer {
        /** 线程池常驻线程数。设置为预期"持续并发任务量的下限"。 */
        private int corePoolSize = 1;

        /** 线程池最大线程数（同时也是 Reviewer 真正能并行的上限）。
         *  注意：值要 ≥ corePoolSize，否则线程池初始化报错；
         *  调大可加快总耗时，但会同时压向 LLM 提供商，可能触发 RPM/TPM 限流。 */
        private int parallelMax = 1;

        /** 线程池任务队列容量。任务多于 parallelMax 时先排队，
         *  队列满才会触发拒绝策略（默认 AbortPolicy 抛 RejectedExecutionException）。 */
        private int queueCapacity = 10;

        /** 单个 Reviewer 任务的超时（秒）。
         *  与 Summarizer.reserveSeconds 共同消耗 totalTimeoutSeconds 总预算：
         *  Orchestrator 在收割每个任务时会动态计算"剩余 deadline - reserveSeconds"作为实际超时上限，
         *  保证即使所有 Reviewer 跑满，Summarizer 仍有 reserveSeconds 秒完成汇总。 */
        private int timeoutSeconds = 120;

        /** Reviewer 单次任务的最大尝试次数（含首发 + 重试，参见 Dispatcher.maxAttempts 的循环说明）。 */
        private int maxAttempts = 1;
    }

    /**
     * Summarizer（汇总 Agent）配置。
     * Summarizer 不一定调 LLM——本地完成冲突检测、verdict 决议、reason 拼装；
     * 仅当 verificationEnabled=true 且 finding 命中阈值时才会触发 LLM 二次验证。
     */
    @Data
    public static class Summarizer {
        /** 给 Summarizer 预留的最小执行时间（秒），从 totalTimeoutSeconds 总预算中切走。
         *  即使所有 Reviewer 阶段卡到 deadline，Summarizer 仍能拿到这段时间完成报告生成。 */
        private int reserveSeconds = 30;

        /** 是否对高风险/低置信度的 finding 触发 LLM 二次验证。
         *  默认关闭：开启会显著增加 LLM 调用量与成本，仅在规则覆盖度还不稳定的早期阶段建议开启。 */
        private boolean verificationEnabled = false;

        /** 二次验证使用的 LLM 提供商（与主审用的 provider 解耦，便于 OpenAI ↔ Anthropic 交叉互验）。 */
        private Provider verifyProvider = Provider.ANTHROPIC;

        /** 触发二次验证的 confidence 阈值。
         *  仅当 finding.riskLevel == HIGH 且 confidence &lt; 该阈值才会被送去二次验证；
         *  调高（如 0.9）→ 更多 finding 被验证，成本上升；调低（如 0.5）→ 验证范围更窄。 */
        private double verifyConfidenceThreshold = 0.7d;

        /** 二次验证 LLM 调用的最大尝试次数。 */
        private int maxAttempts = 1;

        /** 二次验证 LLM 调用的单次超时（秒）。 */
        private int timeoutSeconds = 30;
    }

    @Data
    public static class Runner {
        /** 异步审图 run 的常驻线程数。 */
        private int corePoolSize = 1;

        /** 异步审图 run 的最大并行数。 */
        private int parallelMax = 2;

        /** 等待执行的 run 队列长度。 */
        private int queueCapacity = 20;
    }

    @Data
    public static class ContextBudgetConfig {
        /** Dispatcher 阶段最大输入字符数。 */
        private int dispatcherMaxChars = 60000;

        /** 任务前置清洗阶段最大输入字符数。 */
        private int preCleanerMaxChars = 50000;

        /** Reviewer 阶段最大输入字符数。 */
        private int reviewerMaxChars = 50000;

        /** Summarizer / Verifier 阶段最大输入字符数。 */
        private int summarizerMaxChars = 30000;

        /** 首轮数组裁剪保留条数。 */
        private int firstPassArrayItems = 80;

        /** 二轮数组裁剪保留条数。 */
        private int secondPassArrayItems = 24;

        /** 最后一轮仍超长时保留的关键数组条数。 */
        private int finalPassArrayItems = 8;

        /** 单个长文本字段最大字符数。 */
        private int maxTextChars = 1600;
    }
}

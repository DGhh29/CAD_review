package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CAD IR 清洗器：把 CAD 解析器吐出来的"原始全量 IR"（图层/块/文本/标注/实体）
 * 转换成审图专用的"清洗 + 分组 + 抽样"上下文，给 LLM 用作分派/审核证据。
 *
 * <p>它被 {@link IrViewService#buildSummary} 调用，输出会嵌入到 summary.review_context 字段。
 * 在多 Agent 流水线中位于"解析 → 视图构建"的中间环节，是控制 LLM 上下文质量与体积的关键。
 *
 * <p>核心动作：
 * <ul>
 *   <li><b>主图范围估算</b>：用 1%/99% 分位数对全量坐标做稳健 bbox（{@link RobustBbox}），
 *       低于阈值时回退到 summary.bbox，避免被极少量离群点拉爆主图范围。</li>
 *   <li><b>剔噪</b>：去掉空图层 / 关闭冻结图层 / 未引用块定义 / 短编号文字 / 离群对象 /
 *       零或负 measurement 的尺寸 —— 这些对审图无价值。</li>
 *   <li><b>语义分组</b>：用关键词字典把保留下来的对象分到消防、给排水、暖通、电气、结构、幕墙、
 *       园林、停车、道路、边界、尺寸、墙体、通用备注与未分类等证据组里，让 LLM 能按业务维度找证据。</li>
 *   <li><b>quality 报告</b>：记录主图范围算法、各类对象保留/丢弃数量、以及若干局限性提示。</li>
 * </ul>
 *
 * <p>所有上限常量（MAX_CLEAN_*、MAX_GROUP_ITEMS）都是经验值，目的是把单次 LLM 输入控制在合理 token 预算内。
 */
@Component
public class CadIrCleaner {

    /** 各类对象清洗后保留的硬上限。MAX_CLEAN_TEXTS / MAX_ENTITY_SAMPLES 偏大，是因为文本/实体的信息密度高。 */
    private static final int MAX_CLEAN_LAYERS = 80;
    private static final int MAX_CLEAN_BLOCKS = 80;
    private static final int MAX_CLEAN_TEXTS = 180;
    private static final int MAX_CLEAN_DIMENSIONS = 120;
    private static final int MAX_ENTITY_SAMPLES = 180;
    /** 单个证据组下每个 field 的硬上限，防止某类对象被错误归类后把一个组撑爆。 */
    private static final int MAX_GROUP_ITEMS = 80;
    private static final String PRIORITY_EVIDENCE = "priority_evidence";

    /** 形如 "A12-3"、"BLK_01" 的短编号文本：单独出现没有审图价值，会被丢弃。 */
    private static final Pattern SHORT_CODE = Pattern.compile("^[A-Z0-9_\\-]{4,12}$");
    /** 纯数字文本，孤立时无意义；只有当它落在某个有语义锚点附近时才作为"上下文数字"保留。 */
    private static final Pattern PURE_NUMBER = Pattern.compile("^[-+]?\\d+(?:\\.\\d+)?$");
    /** 尺寸/标注类文本：含 L=、R=、H=、比例号、单位等典型尺寸语法，要保留进 dimensions 组。 */
    private static final Pattern DIMENSION_TEXT = Pattern.compile(
            ".*([LRH]=\\s*[-+]?\\d+(?:\\.\\d+)?|^R\\s*\\d+(?:\\.\\d+)?|\\d+\\s*[:：]\\s*\\d+|\\d+(?:\\.\\d+)?\\s*(m|mm|㎡|%)|%%%).*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** 楼层标注（如 "5F"、"3F/1D"），归类到 building_info 组。 */
    private static final Pattern FLOOR_TEXT = Pattern.compile(".*\\d+\\s*F(?:\\s*/\\s*\\d+\\s*D)?.*", Pattern.CASE_INSENSITIVE);

    /**
     * 证据组顺序：先放常见审图专业，再放通用注释和未分类证据，
     * 报告/前端按此顺序展示更符合审图人员的阅读习惯。
     */
    private static final List<String> GROUP_ORDER = List.of(
            "fire",
            "plumbing",
            "hvac",
            "electrical",
            "structure",
            "curtain_wall",
            "parking",
            "site_boundary",
            "road",
            "landscape",
            "building_info",
            "dimensions",
            "walls",
            "general_notes",
            "unclassified"
    );

    private static final List<String> GEOMETRY_REQUIRED_REVIEW_TYPES = List.of(
            "clear_distance",
            "closed_area",
            "avoidance",
            "collision"
    );

    /**
     * 各证据组的中英文关键词字典。文本/图层/语义里出现任意一个关键词就归到该组，
     * general_notes / unclassified 不在这里出现 —— 它们作为"无法分组时的兜底组"由清洗流程处理。
     */
    private static final Map<String, List<String>> GROUP_KEYWORDS = Map.ofEntries(
            Map.entry("fire", List.of("fire", "消防", "防火", "疏散", "消火栓", "喷淋", "报警", "控制室", "消防车道", "消防车出入口")),
            Map.entry("plumbing", List.of("plumbing", "给排水", "给水", "排水", "雨水", "污水", "废水", "喷淋管", "水管", "管径", "阀门", "水泵", "泵房", "检查井")),
            Map.entry("hvac", List.of("hvac", "暖通", "空调", "通风", "排烟", "风管", "风机", "风口", "新风", "送风", "回风", "防火阀", "排风", "冷媒", "冷冻水")),
            Map.entry("electrical", List.of("electrical", "电气", "配电", "电缆", "桥架", "照明", "插座", "强电", "弱电", "火灾报警", "消防电", "配电箱", "电井", "接地")),
            Map.entry("structure", List.of("structure", "结构", "梁", "柱", "板", "基础", "剪力墙", "钢筋", "桩", "承台", "结构墙", "结构柱")),
            Map.entry("curtain_wall", List.of("curtain wall", "curtain_wall", "幕墙", "龙骨", "铝板", "玻璃", "石材", "挂件", "预埋件", "立柱", "横梁", "防火封堵")),
            Map.entry("parking", List.of("parking", "车位", "停车", "机动车", "非机动车", "充电桩", "车库")),
            Map.entry("site_boundary", List.of("红线", "用地", "宗地", "选址范围", "地界", "退界", "范围")),
            Map.entry("road", List.of("road", "道路", "车道", "出入口", "坡道", "路", "转弯半径")),
            Map.entry("landscape", List.of("landscape", "园林", "景观", "绿化", "种植", "乔木", "灌木", "草坪", "铺装", "排水沟", "雨水口", "标高", "竖向", "高差")),
            Map.entry("building_info", List.of("总平面图", "建筑", "厂房", "耐火等级", "地下室", "物业", "公厕", "层", "高度", "日照", "日照分析", "承诺书")),
            Map.entry("dimensions", List.of("dim", "dimension", "尺寸", "标注", "半径", "坡度", "宽", "高", "长", "L=", "H=", "R=")),
            Map.entry("walls", List.of("wall", "墙", "挡土墙", "剪力墙", "墙体"))
    );

    private final ObjectMapper objectMapper;

    public CadIrCleaner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 主入口：从原始 drawingIr 构建嵌入到 review_context 字段的清洗+分组上下文。
     *
     * <p>步骤：
     * <ol>
     *   <li>抽出 drawing 元信息（schema/source/metadata/summary/statistics + semantic_counts）。</li>
     *   <li>用 P01/P99 计算稳健 bbox + 收集隐藏图层名集合，作为后续剔噪的判据。</li>
     *   <li>顺序清洗 layers / blocks，再以"先收集语义文本锚点 → 再清洗其他文本"的两段式处理 texts，
     *       让纯数字文本能借助附近锚点判断是否值得保留。</li>
     *   <li>清洗 dimensions / entities，并把它们边清洗边加入对应证据组。</li>
     *   <li>把已保留的 blocks 也按关键词归到证据组。</li>
     *   <li>最后构造 quality 报告 + 给 LLM 的输入提示，避免 LLM 把整批 entities 当主输入用。</li>
     * </ol>
     */
    public JsonNode buildReviewContext(JsonNode drawingIr) {
        ObjectNode context = objectMapper.createObjectNode();
        context.set("drawing", buildDrawingNode(drawingIr));

        RobustBbox mainBbox = computeRobustBbox(drawingIr);
        Set<String> hiddenLayers = hiddenLayerNames(drawingIr.path("layers"));

        CleanCounters counters = new CleanCounters();
        ArrayNode cleanLayers = cleanLayers(drawingIr.path("layers"), counters);
        ArrayNode cleanBlocks = cleanBlocks(drawingIr.path("blocks"), counters);

        List<JsonNode> textAnchors = collectMeaningfulTextAnchors(drawingIr.path("texts"), hiddenLayers, mainBbox);
        double textContextRadius = mainBbox.contextRadius();
        ArrayNode cleanTexts = objectMapper.createArrayNode();
        ObjectNode groups = initGroups();
        cleanTexts(drawingIr.path("texts"), hiddenLayers, mainBbox, textAnchors, textContextRadius, cleanTexts, groups, counters);

        ArrayNode cleanDimensions = cleanDimensions(drawingIr.path("dimensions"), hiddenLayers, mainBbox, groups, counters);
        ArrayNode cleanEntitySamples = cleanEntities(drawingIr.path("entities"), hiddenLayers, mainBbox, groups, counters);
        addBlocksToGroups(cleanBlocks, groups, counters);

        context.set("quality", buildQualityNode(drawingIr, mainBbox, counters, groups));
        context.set("clean_layers", cleanLayers);
        context.set("clean_blocks", cleanBlocks);
        context.set("clean_texts", cleanTexts);
        context.set("clean_dimensions", cleanDimensions);
        context.set("clean_entity_samples", cleanEntitySamples);
        context.set("evidence_groups", groups);
        context.set("detected_disciplines", buildDetectedDisciplines(groups));
        context.set("review_readiness", buildReviewReadiness(groups, counters));
        context.put("llm_input_hint", "优先使用 evidence_groups、clean_texts、clean_dimensions 和 quality；不要把全量 entities 直接作为大模型输入。");
        return context;
    }

    /** 抽取图纸元信息 + 语义计数，给 quality 与 review_context 提供"图本身长什么样"的全局描述。 */
    private ObjectNode buildDrawingNode(JsonNode drawingIr) {
        ObjectNode drawing = objectMapper.createObjectNode();
        copyIfPresent(drawing, drawingIr, "schema_version");
        copyIfPresent(drawing, drawingIr, "success");
        copyIfPresent(drawing, drawingIr, "source");
        copyIfPresent(drawing, drawingIr, "metadata");
        copyIfPresent(drawing, drawingIr, "summary");
        copyIfPresent(drawing, drawingIr, "statistics");
        JsonNode semanticCounts = drawingIr.path("semantic").path("counts");
        if (!semanticCounts.isMissingNode()) {
            drawing.set("semantic_counts", semanticCounts.deepCopy());
        }
        return drawing;
    }

    /**
     * 清洗图层：丢弃 entity_count<=0 的空图层、丢弃没有语义注解的隐藏（关闭/冻结）图层。
     * 隐藏但带 semantic 的保留下来 —— 设计图常把"建议性"图层关闭，但 semantic 标注仍有审图价值。
     */
    private ArrayNode cleanLayers(JsonNode layersNode, CleanCounters counters) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode layer : iterable(layersNode)) {
            counters.totalLayers++;
            int entityCount = layer.path("entity_count").asInt(0);
            boolean hidden = isHiddenLayer(layer);
            if (entityCount <= 0) {
                counters.emptyLayers++;
                continue;
            }
            if (hidden) {
                counters.hiddenLayersWithEntitiesSeen++;
                if (isBlank(layer.path("semantic").asText(null))) {
                    counters.hiddenLayersDropped++;
                    continue;
                }
            }
            addLimited(result, compactLayer(layer), MAX_CLEAN_LAYERS);
            counters.keptLayers++;
        }
        return result;
    }

    /**
     * 清洗块定义：剔除内部块（"*"开头）与未引用块。
     * insert_count<=0 且无 semantic 视为"块定义在图里没用"，没必要喂给 LLM。
     */
    private ArrayNode cleanBlocks(JsonNode blocksNode, CleanCounters counters) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode block : iterable(blocksNode)) {
            counters.totalBlocks++;
            int insertCount = block.path("insert_count").asInt(0);
            String semantic = block.path("semantic").asText(null);
            String name = block.path("name").asText("");
            if (name.startsWith("*") && insertCount <= 0 && isBlank(semantic)) {
                counters.internalBlocks++;
                continue;
            }
            if (insertCount <= 0 && isBlank(semantic)) {
                counters.unusedBlocks++;
                continue;
            }
            addLimited(result, compactBlock(block), MAX_CLEAN_BLOCKS);
            counters.keptBlocks++;
        }
        return result;
    }

    /**
     * 收集"语义锚点"文本：在主图范围内、非隐藏图层、非短编号、非纯数字、且符合"有意义文本"判定的文本。
     * 这些锚点用于后续给孤立纯数字文本判定上下文：纯数字若靠近一个消防/标注语义锚点，仍值得保留。
     */
    private List<JsonNode> collectMeaningfulTextAnchors(JsonNode textsNode, Set<String> hiddenLayers, RobustBbox mainBbox) {
        List<JsonNode> anchors = new ArrayList<>();
        for (JsonNode text : iterable(textsNode)) {
            String content = normalizeText(text.path("text").asText(""));
            if (content.isBlank() || hiddenLayers.contains(normalizeKey(text.path("layer").asText("")))) {
                continue;
            }
            if (mainBbox.isOutlier(text)) {
                continue;
            }
            if (isAnchorText(content)) {
                anchors.add(text);
            }
        }
        return anchors;
    }

    /**
     * 主文本清洗 + 分组流程。
     *
     * <p>每条文本依次过四道筛：
     * <ol>
     *   <li>空内容、隐藏图层、离群（远离主图）—— 直接丢弃，分别计入对应计数器。</li>
     *   <li>{@link #classifyText} 决定是否保留以及归属哪些证据组（短编号丢弃，纯数字需要附近锚点）。</li>
     *   <li>保留下来的文本同时压入 cleanTexts 列表（受 MAX_CLEAN_TEXTS 限制）。</li>
     *   <li>分组：有匹配组就加入对应组，没有就兜底加 general_notes。</li>
     * </ol>
     */
    private void cleanTexts(
            JsonNode textsNode,
            Set<String> hiddenLayers,
            RobustBbox mainBbox,
            List<JsonNode> textAnchors,
            double textContextRadius,
            ArrayNode cleanTexts,
            ObjectNode groups,
            CleanCounters counters) {
        for (JsonNode text : iterable(textsNode)) {
            counters.totalTexts++;
            String content = normalizeText(text.path("text").asText(""));
            String layer = normalizeKey(text.path("layer").asText(""));
            if (content.isBlank()) {
                counters.emptyTexts++;
                continue;
            }
            boolean priorityText = isPriorityTextEvidence(content, layer);
            boolean hiddenLayer = hiddenLayers.contains(layer);
            if (hiddenLayer && !priorityText) {
                counters.hiddenLayerTexts++;
                continue;
            }
            boolean outlier = mainBbox.isOutlier(text);
            if (outlier && !priorityText) {
                counters.outlierTexts++;
                continue;
            }
            TextDecision decision = classifyText(text, content, textAnchors, textContextRadius);
            if (!decision.keep() && !priorityText) {
                counters.noiseTexts++;
                continue;
            }
            Set<String> targetGroups = decision.keep() ? new LinkedHashSet<>(decision.groups()) : new LinkedHashSet<>();
            if (priorityText) {
                targetGroups.add("fire");
            }
            ObjectNode compact = compactText(text, decision.keep() ? decision.reason() : "priority_fire_facility_text");
            if (priorityText) {
                compact.put(PRIORITY_EVIDENCE, true);
                compact.put("priority_reason", "fire_facility_text_or_layer");
                if (hiddenLayer) {
                    compact.put("quality_flag", "priority_text_from_hidden_layer");
                } else if (outlier) {
                    compact.put("quality_flag", "priority_text_outside_main_bbox");
                }
                counters.priorityTexts++;
            }
            addLimited(cleanTexts, compact, MAX_CLEAN_TEXTS);
            counters.keptTexts++;
            if (targetGroups.isEmpty()) {
                addToGroup(groups, "general_notes", "texts", compact);
                addToGroup(groups, "unclassified", "texts", compact);
                counters.unclassifiedTexts++;
            } else {
                for (String group : targetGroups) {
                    addToGroup(groups, group, "texts", compact);
                }
            }
        }
    }

    /**
     * 清洗 dimension 标注：
     * <ul>
     *   <li>隐藏图层、离群、缺 measurement 字段、measurement<=0 全部丢弃。</li>
     *   <li>measurement<0.05 视为"可疑过小"（很可能单位/比例错乱），保留但打 quality_flag 让 LLM 警觉。</li>
     *   <li>所有保留的标注都进入 dimensions 证据组，并受 MAX_CLEAN_DIMENSIONS 限制。</li>
     * </ul>
     */
    private ArrayNode cleanDimensions(
            JsonNode dimensionsNode,
            Set<String> hiddenLayers,
            RobustBbox mainBbox,
            ObjectNode groups,
            CleanCounters counters) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode dimension : iterable(dimensionsNode)) {
            counters.totalDimensions++;
            if (hiddenLayers.contains(normalizeKey(dimension.path("layer").asText("")))) {
                counters.hiddenLayerDimensions++;
                continue;
            }
            if (mainBbox.isOutlier(dimension)) {
                counters.outlierDimensions++;
                continue;
            }
            if (!dimension.hasNonNull("measurement")) {
                counters.invalidDimensions++;
                continue;
            }
            double measurement = dimension.path("measurement").asDouble(0.0d);
            if (measurement <= 0.0d) {
                counters.invalidDimensions++;
                continue;
            }
            ObjectNode compact = compactDimension(dimension);
            if (measurement < 0.05d) {
                compact.put("quality_flag", "too_small_verify_unit_or_scale");
                counters.suspiciousDimensions++;
            }
            addLimited(result, compact, MAX_CLEAN_DIMENSIONS);
            counters.keptDimensions++;
            addToGroup(groups, "dimensions", "dimensions", compact);
        }
        return result;
    }

    /**
     * 抽样清洗实体（entities）。
     *
     * <p>原始 entities 一张图可能有几万条，全量喂给 LLM 既慢又烧钱。这里：
     * <ul>
     *   <li>隐藏图层且无语义 / 离群且无语义 —— 丢弃。</li>
     *   <li>{@link #classifyBySemanticLayerAndType} 能归组则进对应专业组；否则保留一份抽样到 unclassified，避免未知专业证据丢失。</li>
     *   <li>受 MAX_ENTITY_SAMPLES 限制总量，超出部分不再加 cleanEntitySamples 但仍在计数器累加。</li>
     * </ul>
     */
    private ArrayNode cleanEntities(
            JsonNode entitiesNode,
            Set<String> hiddenLayers,
            RobustBbox mainBbox,
            ObjectNode groups,
            CleanCounters counters) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode entity : iterable(entitiesNode)) {
            counters.totalEntities++;
            String layer = normalizeKey(entity.path("layer").asText(""));
            String semantic = normalizeKey(entity.path("semantic").asText(""));
            boolean priorityEntity = isPriorityEntityEvidence(entity);
            boolean hiddenLayer = hiddenLayers.contains(layer);
            if (hiddenLayer && isBlank(semantic) && !priorityEntity) {
                counters.hiddenLayerEntities++;
                continue;
            }
            boolean outlier = mainBbox.isOutlier(entity);
            if (outlier && isBlank(semantic) && !priorityEntity) {
                counters.outlierEntities++;
                continue;
            }
            Set<String> groupsForEntity = new LinkedHashSet<>(classifyBySemanticLayerAndType(entity));
            if (priorityEntity) {
                groupsForEntity.add("fire");
            }
            if (groupsForEntity.isEmpty()) {
                ObjectNode compact = compactEntity(entity);
                compact.put("keep_reason", "unclassified_entity_sample");
                addLimited(result, compact, MAX_ENTITY_SAMPLES);
                counters.keptEntitySamples++;
                counters.unclassifiedEntities++;
                addToGroup(groups, "unclassified", "entities", compact);
                continue;
            }
            ObjectNode compact = compactEntity(entity);
            if (priorityEntity) {
                compact.put(PRIORITY_EVIDENCE, true);
                compact.put("priority_reason", "fire_facility_entity_or_layer");
                if (hiddenLayer) {
                    compact.put("quality_flag", "priority_entity_from_hidden_layer");
                } else if (outlier) {
                    compact.put("quality_flag", "priority_entity_outside_main_bbox");
                }
                counters.priorityEntities++;
            }
            addLimited(result, compact, MAX_ENTITY_SAMPLES);
            counters.keptEntitySamples++;
            for (String group : groupsForEntity) {
                addToGroup(groups, group, "entities", compact);
            }
        }
        return result;
    }

    /** 把已保留的 blocks 按名字/语义关键词归到对应证据组；无法归组的块保留到 unclassified。 */
    private void addBlocksToGroups(ArrayNode cleanBlocks, ObjectNode groups, CleanCounters counters) {
        for (JsonNode block : cleanBlocks) {
            Set<String> blockGroups = classifyByTextLayerOrSemantic(
                    block.path("name").asText(""),
                    block.path("name").asText(""),
                    block.path("semantic").asText(""));
            if (blockGroups.isEmpty()) {
                addToGroup(groups, "unclassified", "blocks", block);
                counters.unclassifiedBlocks++;
                continue;
            }
            for (String group : blockGroups) {
                addToGroup(groups, group, "blocks", block);
            }
        }
    }

    /**
     * 构造 quality 报告 —— 记录解析质量、主图范围、各类对象的丢弃/保留计数，并把可能影响审图结论的局限性
     * 以中文 limitations 文本暴露给 LLM。这相当于"我们已经预先告诉 LLM 数据里有哪些坑"。
     */
    private ObjectNode buildQualityNode(JsonNode drawingIr, RobustBbox mainBbox, CleanCounters counters, ObjectNode groups) {
        ObjectNode quality = objectMapper.createObjectNode();
        quality.put("parse_success", drawingIr.path("success").asBoolean(false));
        quality.put("entity_truncated", drawingIr.path("summary").path("entity_truncated").asBoolean(false));
        quality.put("main_bbox_method", mainBbox.method());
        ObjectNode bbox = quality.putObject("main_bbox");
        bbox.put("min_x", mainBbox.minX());
        bbox.put("min_y", mainBbox.minY());
        bbox.put("max_x", mainBbox.maxX());
        bbox.put("max_y", mainBbox.maxY());

        ObjectNode dropped = quality.putObject("dropped_counts");
        dropped.put("empty_layers", counters.emptyLayers);
        dropped.put("hidden_layers_without_semantic", counters.hiddenLayersDropped);
        dropped.put("unused_blocks", counters.unusedBlocks);
        dropped.put("noise_texts", counters.noiseTexts);
        dropped.put("hidden_layer_texts", counters.hiddenLayerTexts);
        dropped.put("outlier_texts", counters.outlierTexts);
        dropped.put("hidden_layer_entities", counters.hiddenLayerEntities);
        dropped.put("outlier_entities", counters.outlierEntities);
        dropped.put("invalid_dimensions", counters.invalidDimensions);
        dropped.put("outlier_dimensions", counters.outlierDimensions);

        ObjectNode kept = quality.putObject("kept_counts");
        kept.put("layers", counters.keptLayers);
        kept.put("texts", counters.keptTexts);
        kept.put("dimensions", counters.keptDimensions);
        kept.put("entity_samples", counters.keptEntitySamples);
        kept.put("blocks", counters.keptBlocks);
        kept.put("unclassified_texts", counters.unclassifiedTexts);
        kept.put("unclassified_entities", counters.unclassifiedEntities);
        kept.put("unclassified_blocks", counters.unclassifiedBlocks);
        kept.put("priority_texts", counters.priorityTexts);
        kept.put("priority_entities", counters.priorityEntities);

        ArrayNode limitations = quality.putArray("limitations");
        if (quality.path("entity_truncated").asBoolean(false)) {
            limitations.add("解析器返回的 entities 明细被截断，依赖全量图元的审核项需要降置信度。");
        }
        if (counters.unclassifiedTexts + counters.unclassifiedEntities + counters.unclassifiedBlocks > 0) {
            limitations.add("存在未能归入已知专业组的证据，已保留到 unclassified，自动审核结论需要降置信度或走人工复核。");
        }
        if (counters.unusedBlocks > 0) {
            limitations.add("存在大量未引用块定义，已从审图上下文剔除。");
        }
        if (counters.hiddenLayersWithEntitiesSeen > 0) {
            limitations.add("存在关闭或冻结图层且含图元，默认不作为强审核证据。");
        }
        if (counters.outlierEntities + counters.outlierTexts + counters.outlierDimensions > 0) {
            limitations.add("检测到远离主图范围的离群对象，已从审图上下文剔除或降权。");
        }
        if (counters.suspiciousDimensions > 0) {
            limitations.add("存在极小尺寸值，可能与单位、比例或尺寸样式有关，需人工或几何规则复核。");
        }
        if (drawingIr.path("dimensions").isArray() && allDimensionTextsBlank(drawingIr.path("dimensions"))) {
            limitations.add("DIMENSION 标注文本为空，仅能使用 measurement 数值，不能替代图面标注文本证据。");
        }
        JsonNode semanticCounts = drawingIr.path("semantic").path("counts");
        for (String group : GROUP_ORDER) {
            int semanticCount = semanticCounts.path(group).asInt(0);
            if (semanticCount > 0 && groupEvidenceCount(groups.path(group)) == 0) {
                limitations.add("原始语义统计显示 " + group + "=" + semanticCount
                        + "，但清洗后的 evidence_groups 未保留对应证据；可能是解析明细截断、图元被剔噪或关键词锚点缺失。");
            }
        }
        limitations.add("净距、面积闭合、避让、碰撞等精确几何审核需要几何规则引擎计算，当前清洗结果仅提供证据上下文。");
        return quality;
    }

    private int groupEvidenceCount(JsonNode groupNode) {
        if (groupNode == null || groupNode.isMissingNode() || groupNode.isNull()) {
            return 0;
        }
        return groupNode.path("texts").size()
                + groupNode.path("dimensions").size()
                + groupNode.path("entities").size()
                + groupNode.path("blocks").size();
    }

    /**
     * 从 evidence_groups 反推当前图纸可能涉及的专业。它不是审核结论，只是给 Dispatcher / 前端
     * 一个可解释的"本次清洗看到了哪些专业证据"。
     */
    private ArrayNode buildDetectedDisciplines(ObjectNode groups) {
        ArrayNode result = objectMapper.createArrayNode();
        for (String group : GROUP_ORDER) {
            if ("general_notes".equals(group)) {
                continue;
            }
            int evidenceCount = evidenceCount(groups.path(group));
            if (evidenceCount <= 0) {
                continue;
            }
            ObjectNode item = result.addObject();
            item.put("name", group);
            item.put("evidence_count", evidenceCount);
            item.put("confidence", disciplineConfidence(evidenceCount));
        }
        return result;
    }

    /**
     * 描述清洗结果可用于哪类自动审核。语义证据可以直接给 LLM/规则分派使用；
     * 几何精算项只声明需要 geometry engine，避免 Reviewer 把清洗上下文当成几何计算结果。
     */
    private ObjectNode buildReviewReadiness(ObjectNode groups, CleanCounters counters) {
        ObjectNode readiness = objectMapper.createObjectNode();
        readiness.put("auto_review_level", "semantic_evidence_only");
        readiness.put("semantic_evidence_ready", hasNonFallbackEvidence(groups));
        readiness.put("geometry_engine_required", true);

        ArrayNode requiredFor = readiness.putArray("geometry_engine_required_for");
        for (String type : GEOMETRY_REQUIRED_REVIEW_TYPES) {
            requiredFor.add(type);
        }

        readiness.put("unclassified_evidence_count",
                counters.unclassifiedTexts + counters.unclassifiedEntities + counters.unclassifiedBlocks);
        ArrayNode warnings = readiness.putArray("warnings");
        warnings.add("当前清洗器会保留专业证据，但不输出净距、闭合面积、避让、碰撞等计算结果。");
        if (readiness.path("unclassified_evidence_count").asInt() > 0) {
            warnings.add("存在未归类证据，后续审核应降低置信度或要求人工复核。");
        }
        return readiness;
    }

    private boolean hasNonFallbackEvidence(ObjectNode groups) {
        for (String group : GROUP_ORDER) {
            if ("general_notes".equals(group) || "unclassified".equals(group)) {
                continue;
            }
            if (evidenceCount(groups.path(group)) > 0) {
                return true;
            }
        }
        return false;
    }

    private int evidenceCount(JsonNode groupNode) {
        return groupNode.path("texts").size()
                + groupNode.path("dimensions").size()
                + groupNode.path("entities").size()
                + groupNode.path("blocks").size();
    }

    private String disciplineConfidence(int evidenceCount) {
        if (evidenceCount >= 5) {
            return "high";
        }
        if (evidenceCount >= 2) {
            return "medium";
        }
        return "low";
    }

    /**
     * 初始化所有证据组容器：每个组下预置 texts/dimensions/entities/blocks 四种空列表，
     * 后续 addToGroup 可以无脑追加，不用做存在性判断。
     */
    private ObjectNode initGroups() {
        ObjectNode groups = objectMapper.createObjectNode();
        for (String group : GROUP_ORDER) {
            ObjectNode bucket = groups.putObject(group);
            bucket.putArray("texts");
            bucket.putArray("dimensions");
            bucket.putArray("entities");
            bucket.putArray("blocks");
        }
        return groups;
    }

    /**
     * 单条文本的去留判定 + 分组。
     * <ul>
     *   <li>短编号文本 —— 直接 drop。</li>
     *   <li>纯数字 —— 只在主图范围内有附近语义锚点时才保留，归到锚点的组里（"context_number"）。</li>
     *   <li>其他文本 —— 用关键词分组；如果没归到任何业务组且本身也没"中文/比例/= 等有意义特征"，drop；
     *       否则按是否包含中文标 "semantic_text" 或 "pattern_text"。</li>
     * </ul>
     */
    private TextDecision classifyText(JsonNode text, String content, List<JsonNode> textAnchors, double radius) {
        if (isShortCode(content)) {
            return TextDecision.drop();
        }
        if (PURE_NUMBER.matcher(content).matches()) {
            Set<String> contextGroups = nearbyAnchorGroups(text, textAnchors, radius);
            if (contextGroups.isEmpty()) {
                return TextDecision.drop();
            }
            return new TextDecision(true, "context_number", contextGroups);
        }
        Set<String> groups = classifyByTextLayerOrSemantic(content, text.path("layer").asText(""), null);
        if (groups.isEmpty() && !isMeaningfulText(content)) {
            return TextDecision.drop();
        }
        String reason = hasChinese(content) ? "semantic_text" : "pattern_text";
        return new TextDecision(true, reason, groups);
    }

    /**
     * 给纯数字文本找最近的语义锚点：在所有 anchor 中按欧氏距离最小、且不超过 radius 的那个，
     * 用它的 (text, layer) 反推归属哪些业务组。找不到 → 返回空集 → 上层会丢弃此数字。
     */
    private Set<String> nearbyAnchorGroups(JsonNode text, List<JsonNode> anchors, double radius) {
        Point point = pointOf(text);
        if (point == null) {
            return Set.of();
        }
        double radius2 = radius * radius;
        return anchors.stream()
                .map(anchor -> new AnchorDistance(anchor, squaredDistance(point, pointOf(anchor))))
                .filter(distance -> distance.value() >= 0.0d && distance.value() <= radius2)
                .min(Comparator.comparingDouble(AnchorDistance::value))
                .map(distance -> classifyByTextLayerOrSemantic(
                        distance.anchor().path("text").asText(""),
                        distance.anchor().path("layer").asText(""),
                        null))
                .orElse(Set.of());
    }

    /**
     * 实体分组：综合 text + layer + semantic + type + block 名做关键词匹配。
     * 特殊处理：type=dimension 强行归 dimensions 组；type=insert 时再用 block 名做一次分组合并。
     */
    private Set<String> classifyBySemanticLayerAndType(JsonNode entity) {
        Set<String> groups = classifyByTextLayerOrSemantic(
                entity.path("text").asText(""),
                entity.path("layer").asText(""),
                entity.path("semantic").asText(""));
        String type = normalizeKey(entity.path("type").asText(""));
        if ("dimension".equals(type)) {
            groups.add("dimensions");
        }
        if ("insert".equals(type)) {
            Set<String> byBlock = classifyByTextLayerOrSemantic(
                    entity.path("block").asText(""),
                    entity.path("layer").asText(""),
                    entity.path("semantic").asText(""));
            groups.addAll(byBlock);
        }
        return groups;
    }

    /**
     * 通用关键词分组器：把文本/图层/语义三段拼起来归一化后，与 GROUP_KEYWORDS 做 contains 匹配。
     * 任意命中即把当前组加入返回集合（一个对象可同时归多组）。
     * 另外 DIMENSION_TEXT/FLOOR_TEXT 正则命中时强制并入 dimensions / building_info。
     */
    private Set<String> classifyByTextLayerOrSemantic(String text, String layer, String semantic) {
        Set<String> groups = new LinkedHashSet<>();
        String value = normalizeKey(text) + " " + normalizeKey(layer) + " " + normalizeKey(semantic);
        for (String group : GROUP_ORDER) {
            if ("general_notes".equals(group)) {
                continue;
            }
            List<String> keywords = GROUP_KEYWORDS.getOrDefault(group, List.of());
            for (String keyword : keywords) {
                if (!keyword.isBlank() && value.contains(normalizeKey(keyword))) {
                    groups.add(group);
                    break;
                }
            }
        }
        if (DIMENSION_TEXT.matcher(text).matches()) {
            groups.add("dimensions");
        }
        if (FLOOR_TEXT.matcher(text).matches()) {
            groups.add("building_info");
        }
        return groups;
    }

    private boolean isMeaningfulText(String content) {
        return hasChinese(content)
                || DIMENSION_TEXT.matcher(content).matches()
                || FLOOR_TEXT.matcher(content).matches()
                || content.contains("=")
                || content.contains(":");
    }

    private boolean isAnchorText(String content) {
        return !isShortCode(content) && !PURE_NUMBER.matcher(content).matches() && isMeaningfulText(content);
    }

    private boolean isShortCode(String content) {
        return SHORT_CODE.matcher(content).matches()
                && !DIMENSION_TEXT.matcher(content).matches()
                && !FLOOR_TEXT.matcher(content).matches();
    }

    private boolean allDimensionTextsBlank(JsonNode dimensionsNode) {
        boolean sawDimension = false;
        for (JsonNode dimension : iterable(dimensionsNode)) {
            sawDimension = true;
            if (!dimension.path("text").asText("").isBlank()) {
                return false;
            }
        }
        return sawDimension;
    }

    private ObjectNode compactLayer(JsonNode layer) {
        ObjectNode node = objectMapper.createObjectNode();
        copyIfPresent(node, layer, "name");
        copyIfPresent(node, layer, "entity_count");
        copyIfPresent(node, layer, "semantic");
        copyIfPresent(node, layer, "linetype");
        copyIfPresent(node, layer, "is_off");
        copyIfPresent(node, layer, "is_frozen");
        return node;
    }

    private ObjectNode compactBlock(JsonNode block) {
        ObjectNode node = objectMapper.createObjectNode();
        copyIfPresent(node, block, "name");
        copyIfPresent(node, block, "entity_count");
        copyIfPresent(node, block, "insert_count");
        copyIfPresent(node, block, "semantic");
        return node;
    }

    private ObjectNode compactText(JsonNode text, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        copyIfPresent(node, text, "text");
        copyIfPresent(node, text, "layer");
        copyIfPresent(node, text, "type");
        copyIfPresent(node, text, "point");
        node.put("keep_reason", reason);
        return node;
    }

    private ObjectNode compactDimension(JsonNode dimension) {
        ObjectNode node = objectMapper.createObjectNode();
        copyIfPresent(node, dimension, "layer");
        copyIfPresent(node, dimension, "handle");
        copyIfPresent(node, dimension, "measurement");
        copyIfPresent(node, dimension, "text");
        copyIfPresent(node, dimension, "point");
        return node;
    }

    private ObjectNode compactEntity(JsonNode entity) {
        ObjectNode node = objectMapper.createObjectNode();
        copyIfPresent(node, entity, "index");
        copyIfPresent(node, entity, "handle");
        copyIfPresent(node, entity, "type");
        copyIfPresent(node, entity, "layer");
        copyIfPresent(node, entity, "semantic");
        copyIfPresent(node, entity, "block");
        copyIfPresent(node, entity, "text");
        copyIfPresent(node, entity, "bbox");
        copyIfPresent(node, entity, "insert");
        copyIfPresent(node, entity, "point");
        copyIfPresent(node, entity, "start");
        copyIfPresent(node, entity, "end");
        copyIfPresent(node, entity, "center");
        copyIfPresent(node, entity, "length");
        copyIfPresent(node, entity, "radius");
        return node;
    }

    /**
     * 用 P01/P99 分位数从大量坐标里估算稳健 bbox。
     *
     * <p>原理：极少量解析异常或图框外的辅助元素会让 minX/maxX 漂得很远；通过取 1% / 99% 分位数
     * 去掉两端各 1% 的极端值，得到的范围更贴近"图纸主体"。
     *
     * <p>样本数 < 20 时认为统计意义不足，回退给 summary.bbox（如果 summary 里也没 bbox 就直接走分位数算法）。
     */
    private RobustBbox computeRobustBbox(JsonNode drawingIr) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        collectCoordinates(drawingIr.path("entities"), xs, ys);
        collectCoordinates(drawingIr.path("texts"), xs, ys);
        collectCoordinates(drawingIr.path("dimensions"), xs, ys);
        if (xs.size() < 20) {
            JsonNode bbox = drawingIr.path("summary").path("bbox");
            if (bbox.has("min_x") && bbox.has("max_x") && bbox.has("min_y") && bbox.has("max_y")) {
                return new RobustBbox(
                        bbox.path("min_x").asDouble(),
                        bbox.path("min_y").asDouble(),
                        bbox.path("max_x").asDouble(),
                        bbox.path("max_y").asDouble(),
                        "summary_bbox"
                );
            }
        }
        xs.sort(Double::compareTo);
        ys.sort(Double::compareTo);
        double minX = percentile(xs, 0.01d);
        double maxX = percentile(xs, 0.99d);
        double minY = percentile(ys, 0.01d);
        double maxY = percentile(ys, 0.99d);
        return new RobustBbox(minX, minY, maxX, maxY, "p01_p99_robust_bbox");
    }

    private void collectCoordinates(JsonNode arrayNode, List<Double> xs, List<Double> ys) {
        for (JsonNode node : iterable(arrayNode)) {
            addPoint(node.path("point"), xs, ys);
            addPoint(node.path("insert"), xs, ys);
            addPoint(node.path("center"), xs, ys);
            addPoint(node.path("start"), xs, ys);
            addPoint(node.path("end"), xs, ys);
            for (JsonNode point : iterable(node.path("points"))) {
                addPoint(point, xs, ys);
            }
            JsonNode bbox = node.path("bbox");
            addNumbers(bbox.path("min_x"), bbox.path("min_y"), xs, ys);
            addNumbers(bbox.path("max_x"), bbox.path("max_y"), xs, ys);
        }
    }

    private void addPoint(JsonNode point, List<Double> xs, List<Double> ys) {
        if (point.isArray() && point.size() >= 2) {
            addNumbers(point.get(0), point.get(1), xs, ys);
        }
    }

    private void addNumbers(JsonNode x, JsonNode y, List<Double> xs, List<Double> ys) {
        if (x.isNumber() && y.isNumber()) {
            xs.add(x.asDouble());
            ys.add(y.asDouble());
        }
    }

    private double percentile(List<Double> values, double ratio) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        int index = (int) Math.round((values.size() - 1) * ratio);
        index = Math.max(0, Math.min(values.size() - 1, index));
        return values.get(index);
    }

    /**
     * 把对象追加到指定证据组的指定字段（texts/dimensions/entities/blocks）下。
     * 单字段超过 MAX_GROUP_ITEMS 时静默丢弃，避免某类对象被错误归类后撑爆该组。
     */
    private void addToGroup(ObjectNode groups, String group, String field, JsonNode value) {
        JsonNode groupNode = groups.get(group);
        if (!(groupNode instanceof ObjectNode bucket)) {
            return;
        }
        JsonNode arrayNode = bucket.get(field);
        if (arrayNode instanceof ArrayNode array) {
            addLimited(array, value, MAX_GROUP_ITEMS);
        }
    }

    /** 通用截断追加器：array 未达到上限就 deepCopy 后写入，保证不修改源节点。 */
    private void addLimited(ArrayNode array, JsonNode value, int limit) {
        if (array.size() < limit) {
            array.add(value.deepCopy());
            return;
        }
        if (!isPriorityEvidence(value)) {
            return;
        }
        for (int i = array.size() - 1; i >= 0; i--) {
            if (!isPriorityEvidence(array.get(i))) {
                array.remove(i);
                array.add(value.deepCopy());
                return;
            }
        }
    }

    /** 优先证据用于避免关键消防设施被普通抽样上限挤出。 */
    private boolean isPriorityEvidence(JsonNode value) {
        return value != null && value.path(PRIORITY_EVIDENCE).asBoolean(false);
    }

    private boolean isPriorityTextEvidence(String content, String normalizedLayer) {
        String value = normalizeKey(content) + " " + normalizedLayer;
        return containsFireFacilityKeyword(value);
    }

    private boolean isPriorityEntityEvidence(JsonNode entity) {
        String value = normalizeKey(entity.path("text").asText(""))
                + " " + normalizeKey(entity.path("layer").asText(""))
                + " " + normalizeKey(entity.path("semantic").asText(""))
                + " " + normalizeKey(entity.path("block").asText(""))
                + " " + normalizeKey(entity.path("type").asText(""));
        return containsFireFacilityKeyword(value);
    }

    private boolean containsFireFacilityKeyword(String value) {
        String normalized = normalizeKey(value);
        return containsAny(normalized, "equip_消防", "消火栓", "喷淋", "报警", "消防设施", "灭火器",
                "消防水池", "消防水泵", "消防箱", "消防泵", "消防管", "消防电",
                "hydrant", "sprinkler", "alarm", "fire pump", "fire equipment")
                || (normalized.contains("消防") && (normalized.contains("栓")
                || normalized.contains("泵") || normalized.contains("池") || normalized.contains("箱")))
                || (normalized.contains("喷") && normalized.contains("淋"))
                || (normalized.contains("报") && normalized.contains("警"));
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(normalizeKey(needle))) {
                return true;
            }
        }
        return false;
    }

    /** 收集图层节点里所有"关闭/冻结"图层名，归一化后放入集合，供后续 isHiddenLayer 快速判断。 */
    private Set<String> hiddenLayerNames(JsonNode layersNode) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode layer : iterable(layersNode)) {
            if (isHiddenLayer(layer)) {
                result.add(normalizeKey(layer.path("name").asText("")));
            }
        }
        return result;
    }

    private boolean isHiddenLayer(JsonNode layer) {
        return layer.path("is_off").asBoolean(false) || layer.path("is_frozen").asBoolean(false);
    }

    private void copyIfPresent(ObjectNode target, JsonNode source, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && !value.isNull()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("%%%", "%");
    }

    /** 单值归一化：trim + 转小写。比对图层/关键词时统一走这里，避免大小写/空格不一致漏匹配。 */
    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 从节点里"按优先级"找一个能代表它位置的点。
     * 顺序：point → insert → center → bbox 中心 → start → end。
     * 找不到返回 null，调用方据此决定是否跳过几何相关判定。
     */
    private Point pointOf(JsonNode node) {
        Point point = pointFrom(node.path("point"));
        if (point != null) {
            return point;
        }
        point = pointFrom(node.path("insert"));
        if (point != null) {
            return point;
        }
        point = pointFrom(node.path("center"));
        if (point != null) {
            return point;
        }
        JsonNode bbox = node.path("bbox");
        if (bbox.has("min_x") && bbox.has("max_x") && bbox.has("min_y") && bbox.has("max_y")) {
            return new Point(
                    (bbox.path("min_x").asDouble() + bbox.path("max_x").asDouble()) / 2.0d,
                    (bbox.path("min_y").asDouble() + bbox.path("max_y").asDouble()) / 2.0d
            );
        }
        point = pointFrom(node.path("start"));
        if (point != null) {
            return point;
        }
        return pointFrom(node.path("end"));
    }

    private Point pointFrom(JsonNode point) {
        if (point.isArray() && point.size() >= 2 && point.get(0).isNumber() && point.get(1).isNumber()) {
            return new Point(point.get(0).asDouble(), point.get(1).asDouble());
        }
        return null;
    }

    private double squaredDistance(Point first, Point second) {
        if (first == null || second == null) {
            return -1.0d;
        }
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        return dx * dx + dy * dy;
    }

    /** 单条文本的"去留 + 归类原因 + 归属组"决策结果。reason 会写到 compact 节点里供 LLM 看到为什么这条文本被保留。 */
    private record TextDecision(boolean keep, String reason, Set<String> groups) {
        static TextDecision drop() {
            return new TextDecision(false, "drop", Set.of());
        }
    }

    /** 锚点距离对，用于流式找最近锚点。 */
    private record AnchorDistance(JsonNode anchor, double value) {
    }

    /** 二维坐标点的最小载体。 */
    private record Point(double x, double y) {
    }

    /**
     * 主图稳健 bbox + 算法名。
     *
     * <p>{@link #isOutlier} 判断节点是否离主图过远（带 contextRadius * 2 的边距，避免误杀边缘对象）；
     * {@link #contextRadius} 给出一个根据图纸尺寸自适应的"近邻半径"，作为纯数字找语义锚点的搜索范围。
     */
    private record RobustBbox(double minX, double minY, double maxX, double maxY, String method) {

        boolean isOutlier(JsonNode node) {
            Point point = null;
            if (node.path("point").isArray()) {
                point = pointFromArray(node.path("point"));
            }
            if (point == null && node.path("insert").isArray()) {
                point = pointFromArray(node.path("insert"));
            }
            if (point == null && node.path("center").isArray()) {
                point = pointFromArray(node.path("center"));
            }
            if (point == null && node.path("bbox").has("min_x")) {
                JsonNode bbox = node.path("bbox");
                point = new Point(
                        (bbox.path("min_x").asDouble() + bbox.path("max_x").asDouble()) / 2.0d,
                        (bbox.path("min_y").asDouble() + bbox.path("max_y").asDouble()) / 2.0d
                );
            }
            if (point == null) {
                return false;
            }
            double margin = contextRadius() * 2.0d;
            return point.x() < minX - margin
                    || point.x() > maxX + margin
                    || point.y() < minY - margin
                    || point.y() > maxY + margin;
        }

        double contextRadius() {
            double width = Math.abs(maxX - minX);
            double height = Math.abs(maxY - minY);
            double base = Math.max(20.0d, Math.min(width, height) * 0.06d);
            return Math.min(Math.max(base, 20.0d), 120.0d);
        }

        private static Point pointFromArray(JsonNode point) {
            if (point.isArray() && point.size() >= 2 && point.get(0).isNumber() && point.get(1).isNumber()) {
                return new Point(point.get(0).asDouble(), point.get(1).asDouble());
            }
            return null;
        }
    }

    /** 清洗过程中用到的所有"丢弃/保留"计数器。最终会被写到 quality 报告里供前端展示。 */
    private static class CleanCounters {
        private int totalLayers;
        private int keptLayers;
        private int emptyLayers;
        private int hiddenLayersWithEntitiesSeen;
        private int hiddenLayersDropped;
        private int totalBlocks;
        private int keptBlocks;
        private int unusedBlocks;
        private int internalBlocks;
        private int totalTexts;
        private int keptTexts;
        private int unclassifiedTexts;
        private int emptyTexts;
        private int hiddenLayerTexts;
        private int outlierTexts;
        private int noiseTexts;
        private int totalDimensions;
        private int keptDimensions;
        private int hiddenLayerDimensions;
        private int outlierDimensions;
        private int invalidDimensions;
        private int suspiciousDimensions;
        private int totalEntities;
        private int keptEntitySamples;
        private int unclassifiedEntities;
        private int unclassifiedBlocks;
        private int hiddenLayerEntities;
        private int outlierEntities;
        private int priorityTexts;
        private int priorityEntities;
    }
}

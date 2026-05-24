package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.dto.ReviewTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 喂给 LLM 的"图纸 IR 视图"构造器，负责把解析出来的全量 IR 压缩到不会撑爆上下文。
 *
 * <p>提供两类视图：
 * <ul>
 *   <li>{@link #buildSummary(JsonNode)} —— 给 Dispatcher 看的"摘要视图"。
 *       保留元数据 / 统计 / 语义计数 + 抽样 30~80 条的层/块/文本/标注 + CadIrCleaner 产出的清洗上下文，
 *       让 LLM 知道全图整体长什么样、有哪些区域线索，以便拆任务。</li>
 *   <li>{@link #slice(JsonNode, List, List)} —— 给 Reviewer 看的"切片视图"。
 *       根据 task 给出的 entityIds / layerNames，只挑相关的 entity / text / dimension 切出来，
 *       让单个 Reviewer 调用尽量聚焦。</li>
 * </ul>
 *
 * <p>这两个视图都是只读派生数据，不会破坏原 drawingIr。
 * 上下文体积控制（{@code maxReviewEntities} / 30 / 40 / 80 等限制）来自 {@link AgentProperties}
 * 与本地常量，避免某张超大图把单次请求成本打到天上去。
 */
@Component
@RequiredArgsConstructor
public class IrViewService {

    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final CadIrCleaner cadIrCleaner;

    /**
     * 构建给 Dispatcher 看的图纸摘要：
     * <ol>
     *   <li>整体只读字段直接拷过来：schema_version / success / source / metadata / summary / statistics /
     *       semantic / warnings / audit_pack —— 这些是元信息，体积固定且对 LLM 拆任务有用。</li>
     *   <li>嵌入 {@link CadIrCleaner#buildReviewContext} 的清洗结果到 review_context，
     *       提供按消防/停车/红线等业务分组的"证据组"，是 Dispatcher 拆任务的主要依据。</li>
     *   <li>对 layers / blocks / texts / dimensions 各自截断到固定上限（30/20/40/40），保证规模可控。</li>
     *   <li>实体只抽样 maxReviewEntities 条放在 entitySamples，并记录上限值，让 LLM 知道还有更多没看到。</li>
     * </ol>
     *
     * <p>之所以要这么"做减法"：原始 IR 一张大图轻松上 MB，直接喂 LLM 既费钱又会触发 token 上限。
     */
    public JsonNode buildSummary(JsonNode drawingIr) {
        ObjectNode summary = objectMapper.createObjectNode();
        copyIfPresent(summary, drawingIr, "schema_version");
        copyIfPresent(summary, drawingIr, "success");
        copyIfPresent(summary, drawingIr, "source");
        copyIfPresent(summary, drawingIr, "metadata");
        copyIfPresent(summary, drawingIr, "summary");
        copyIfPresent(summary, drawingIr, "statistics");
        copyIfPresent(summary, drawingIr, "semantic");
        copyIfPresent(summary, drawingIr, "warnings");
        copyIfPresent(summary, drawingIr, "audit_pack");
        // review_context 是经过 CadIrCleaner 业务清洗+分组的核心证据视图，是 Dispatcher 拆任务的主要依据
        summary.set("review_context", cadIrCleaner.buildReviewContext(drawingIr));

        // 上限是经验值：layers 30 个一般够看出图层语义；blocks 20、texts/dimensions 40 同理
        summary.set("layers", limitArray(drawingIr.path("layers"), 30));
        summary.set("blocks", limitArray(drawingIr.path("blocks"), 20));
        summary.set("texts", limitArray(drawingIr.path("texts"), 40));
        summary.set("dimensions", limitArray(drawingIr.path("dimensions"), 40));
        summary.set("entitySamples", sampleEntities(drawingIr.path("entities"), agentProperties.getMaxReviewEntities()));
        summary.put("entitySampleLimit", agentProperties.getMaxReviewEntities());
        return summary;
    }

    /**
     * 构建给单个 Reviewer 看的图纸切片：只保留与本 task 相关的 entity/text/dimension/layer。
     *
     * <p>策略：
     * <ul>
     *   <li>entity 同时根据 entityIds（精确锚点）和 layerNames（图层范围）筛选；
     *       存在 entityIds 时优先精确匹配（handle/index/"entity-NNN"），匹配不上再回退到图层匹配。</li>
     *   <li>text / dimension 则只按 layerNames 过滤 —— 它们没有稳定的实体 ID 概念。</li>
     *   <li>entity 总条数受 maxReviewEntities 上限保护，layer 上限固定 40。</li>
     *   <li>summary / statistics / semantic / warnings 等元信息照搬，让 Reviewer 仍能感知图纸整体上下文。</li>
     * </ul>
     */
    public JsonNode slice(JsonNode drawingIr, List<String> entityIds, List<String> layerNames) {
        ObjectNode slice = objectMapper.createObjectNode();
        copyIfPresent(slice, drawingIr, "schema_version");
        copyIfPresent(slice, drawingIr, "source");
        copyIfPresent(slice, drawingIr, "summary");
        copyIfPresent(slice, drawingIr, "statistics");
        copyIfPresent(slice, drawingIr, "semantic");
        copyIfPresent(slice, drawingIr, "warnings");

        // 全部 key 提前 normalize 成小写去空格，避免大小写/前后空格导致漏匹配
        Set<String> normalizedLayerNames = normalize(layerNames);
        Set<String> normalizedEntityIds = normalize(entityIds);
        ArrayNode entities = objectMapper.createArrayNode();
        ArrayNode texts = objectMapper.createArrayNode();
        ArrayNode dimensions = objectMapper.createArrayNode();

        int maxEntities = agentProperties.getMaxReviewEntities();
        for (JsonNode entity : iterable(drawingIr.path("entities"))) {
            // 切片同样要尊重上限，超过 maxEntities 直接停 —— 单个任务超量说明 Dispatcher 拆得不够细
            if (entities.size() >= maxEntities) {
                break;
            }
            if (matchesEntity(entity, normalizedEntityIds, normalizedLayerNames)) {
                entities.add(entity.deepCopy());
            }
        }
        for (JsonNode text : iterable(drawingIr.path("texts"))) {
            if (matchesLayer(text, normalizedLayerNames)) {
                texts.add(text.deepCopy());
            }
        }
        for (JsonNode dimension : iterable(drawingIr.path("dimensions"))) {
            if (matchesLayer(dimension, normalizedLayerNames)) {
                dimensions.add(dimension.deepCopy());
            }
        }

        slice.set("selectedEntities", entities);
        slice.set("selectedTexts", texts);
        slice.set("selectedDimensions", dimensions);
        slice.set("selectedLayers", limitByLayer(drawingIr.path("layers"), normalizedLayerNames));
        slice.put("selectionHint", buildSelectionHint(entityIds, layerNames, entities.size()));
        return slice;
    }

    /** 抽样实体：按出现顺序取前 N 条，N = min(limit, 80)。80 是硬顶，避免上层配过大反而拖慢 Dispatcher。 */
    private ArrayNode sampleEntities(JsonNode entitiesNode, int limit) {
        ArrayNode samples = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode entity : iterable(entitiesNode)) {
            if (count >= Math.min(limit, 80)) {
                break;
            }
            samples.add(entity.deepCopy());
            count++;
        }
        return samples;
    }

    /** 通用截断器：把任意数组节点按上限 deepCopy 到新 ArrayNode。 */
    private ArrayNode limitArray(JsonNode node, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode item : iterable(node)) {
            if (count >= limit) {
                break;
            }
            result.add(item.deepCopy());
            count++;
        }
        return result;
    }

    /**
     * 按图层名筛选 layers 节点。空筛选条件 = 全保留；上限 40 防止异常配置下塞太多无关图层。
     */
    private ArrayNode limitByLayer(JsonNode layersNode, Set<String> normalizedLayerNames) {
        ArrayNode result = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode layer : iterable(layersNode)) {
            if (count >= 40) {
                break;
            }
            if (normalizedLayerNames.isEmpty() || normalizedLayerNames.contains(normalize(layer.path("name").asText()))) {
                result.add(layer.deepCopy());
                count++;
            }
        }
        return result;
    }

    /**
     * 实体匹配规则（带 entityIds 时为精确优先 + 图层兜底）：
     * <ul>
     *   <li>有 entityIds 时：依次比对 handle / index / "entity-NNN" 三种 id 形式（CAD 解析器在不同后端给出不同格式），
     *       任一命中即返回 true；都没命中再看 layerNames 是否能覆盖（"指定实体所在的图层也一并带上"）。</li>
     *   <li>无 entityIds 时：纯按 layerNames 过滤。</li>
     * </ul>
     */
    private boolean matchesEntity(JsonNode entity, Set<String> normalizedEntityIds, Set<String> normalizedLayerNames) {
        if (!normalizedEntityIds.isEmpty()) {
            String handle = normalize(entity.path("handle").asText(null));
            String index = normalize(entity.path("index").asText(null));
            int parsedIndex = index != null ? parseIntSafe(index) : -1;
            // CAD 解析器在某些路径会给出 "entity-NNN" 形式的合成 ID，这里同步生成做一次比对
            String generated = parsedIndex >= 0 ? "entity-" + String.format(Locale.ROOT, "%03d", parsedIndex) : null;
            if (handle != null && normalizedEntityIds.contains(handle)) {
                return true;
            }
            if (index != null && normalizedEntityIds.contains(index)) {
                return true;
            }
            if (generated != null && normalizedEntityIds.contains(generated)) {
                return true;
            }
            // 精确匹配全部失败时，layerNames 非空才允许图层兜底，避免无 entityIds 时也误判
            return !normalizedLayerNames.isEmpty() && matchesLayer(entity, normalizedLayerNames);
        }
        return matchesLayer(entity, normalizedLayerNames);
    }

    /**
     * 图层匹配：normalizedLayerNames 为空时视为"匹配一切"（不限制）；否则按 layer / name 字段比对小写串。
     * 之所以同时看 layer 和 name —— 不同实体类型字段命名不同（block 用 name，普通 entity 用 layer）。
     */
    private boolean matchesLayer(JsonNode node, Set<String> normalizedLayerNames) {
        if (normalizedLayerNames.isEmpty()) {
            return true;
        }
        String layer = normalize(node.path("layer").asText(null));
        if (layer != null && normalizedLayerNames.contains(layer)) {
            return true;
        }
        String name = normalize(node.path("name").asText(null));
        return name != null && normalizedLayerNames.contains(name);
    }

    /** deepCopy 字段拷贝小工具：source 没有该字段或为 null 时跳过，避免目标节点出现 null 字段。 */
    private void copyIfPresent(ObjectNode target, JsonNode source, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && !value.isNull()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    /** 把一个 JsonNode 当数组遍历，非数组返回空列表，避免 forEach 时抛异常。 */
    private List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    /** 字符串列表归一化为去重小写集合，方便后续按 key 命中。 */
    private Set<String> normalize(List<String> values) {
        Set<String> result = new HashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    /** 单值归一化：空白返回 null（让上层用 null 判空），否则 trim + 小写。 */
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** parseInt 包一层吞异常的版本，用于把 entity index 字符串安全转为整数。 */
    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ex) {
            return -1;
        }
    }

    /**
     * 拼一段简短的"切片摘要"放进切片输出，让 Reviewer 一眼看出输入做了多少筛选 —— 主要给排查/可观察用。
     */
    private String buildSelectionHint(List<String> entityIds, List<String> layerNames, int entityCount) {
        return "entityIds=" + (entityIds == null ? 0 : entityIds.size())
                + ", layerNames=" + (layerNames == null ? 0 : layerNames.size())
                + ", selectedEntities=" + entityCount;
    }
}

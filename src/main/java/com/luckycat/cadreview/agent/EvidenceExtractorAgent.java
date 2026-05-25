package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.config.AgentProperties;
import com.luckycat.cadreview.prompt.PromptTemplates;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量证据抽取 Agent。
 *
 * <p>它只回答“这个 chunk 中有哪些与任务相关的证据”，不做合规审核。
 * 当 LLM 调用失败时，会降级为本地规则抽取，保证补证链路不拖垮主审核流程。
 */
@Slf4j
@Service
public class EvidenceExtractorAgent implements EvidenceExtractionClient {

    private final AgentModelRouter agentModelRouter;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final PromptTemplates promptTemplates;
    private final StructuredOutputSupport structuredOutputSupport;
    private final ContextBudgetService contextBudgetService;

    public EvidenceExtractorAgent(
            AgentModelRouter agentModelRouter,
            AgentProperties agentProperties,
            ObjectMapper objectMapper,
            PromptTemplates promptTemplates,
            StructuredOutputSupport structuredOutputSupport,
            ContextBudgetService contextBudgetService) {
        this.agentModelRouter = agentModelRouter;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.promptTemplates = promptTemplates;
        this.structuredOutputSupport = structuredOutputSupport;
        this.contextBudgetService = contextBudgetService;
    }

    @Override
    public EvidenceExtractionResult extract(EvidenceSearchTask task, EvidenceChunk chunk) {
        try {
            JsonNode promptContext = objectMapper.valueToTree(Map.of(
                    "searchTask", task,
                    "chunk", chunk
            ));
            String userPrompt = contextBudgetService.toPromptJson(
                    contextBudgetService.wrap(AgentRole.EVIDENCE_EXTRACTOR, "EVIDENCE_EXTRACT", task.getTaskId(), promptContext));
            StructuredOutputSupport.StructuredResult<ExtractionOutput> result = structuredOutputSupport.call(
                    agentModelRouter.clientFor(AgentRole.EVIDENCE_EXTRACTOR),
                    promptTemplates.evidenceExtractorSystem(),
                    userPrompt,
                    ExtractionOutput.class,
                    agentProperties.getEvidenceRepair().getMaxAttempts(),
                    "evidence-extractor-" + task.getTaskId() + "-" + chunk.getChunkId(),
                    output -> validateOutput(output, task, chunk)
            );
            return result.getOutput().toResult(task.getTaskId(), chunk.getChunkId());
        } catch (Exception ex) {
            log.warn("Evidence extractor degraded to local extraction for task {} chunk {}: {}",
                    task.getTaskId(), chunk.getChunkId(), ex.getMessage());
            return localExtract(task, chunk);
        }
    }

    private ExtractionOutput validateOutput(ExtractionOutput output, EvidenceSearchTask task, EvidenceChunk chunk) {
        if (output == null) {
            output = new ExtractionOutput();
        }
        output.setTaskId(blankToDefault(output.getTaskId(), task.getTaskId()));
        output.setChunkId(blankToDefault(output.getChunkId(), chunk.getChunkId()));
        if (output.getEvidence() == null) {
            output.setEvidence(new ArrayList<>());
        }
        if (output.getStillMissing() == null) {
            output.setStillMissing(new ArrayList<>());
        }
        for (ExtractedEvidence evidence : output.getEvidence()) {
            normalizeEvidence(evidence);
        }
        output.setRelevant(output.isRelevant() || !output.getEvidence().isEmpty());
        return output;
    }

    private void normalizeEvidence(ExtractedEvidence evidence) {
        if (evidence == null) {
            return;
        }
        if (evidence.getConfidence() == null) {
            evidence.setConfidence(0.7d);
        }
        if (evidence.getConfidence() < 0.0d) {
            evidence.setConfidence(0.0d);
        }
        if (evidence.getConfidence() > 1.0d) {
            evidence.setConfidence(1.0d);
        }
        evidence.setPriorityEvidence(true);
    }

    private EvidenceExtractionResult localExtract(EvidenceSearchTask task, EvidenceChunk chunk) {
        List<ExtractedEvidence> evidence = new ArrayList<>();
        for (JsonNode item : chunk.getItems() == null ? List.<JsonNode>of() : chunk.getItems()) {
            if (matches(item, task)) {
                evidence.add(toEvidence(item, task, chunk));
            }
        }
        return EvidenceExtractionResult.builder()
                .taskId(task.getTaskId())
                .chunkId(chunk.getChunkId())
                .relevant(!evidence.isEmpty())
                .evidence(evidence)
                .stillMissing(remainingMissing(task, evidence))
                .build();
    }

    private boolean matches(JsonNode item, EvidenceSearchTask task) {
        String text = searchableText(item);
        for (String keyword : allHints(task)) {
            if (!keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private ExtractedEvidence toEvidence(JsonNode item, EvidenceSearchTask task, EvidenceChunk chunk) {
        String sourcePath = firstText(item, "sourcePath", "source_path");
        String content = firstText(item, "text", "content", "value", "label", "blockName", "block_name", "name");
        String layer = firstText(item, "layer");
        String entityType = firstText(item, "type", "entityType", "entity_type");
        ExtractedEvidence evidence = ExtractedEvidence.builder()
                .evidenceType(chunk.getChunkType())
                .sourcePath(sourcePath)
                .layer(layer)
                .entityType(entityType)
                .blockName(firstText(item, "blockName", "block_name", "name"))
                .content(content.isBlank() ? searchableText(item) : content)
                .matchedRequirement(firstMatchedRequirement(task, item))
                .reason("本地兜底抽取：chunk 内容命中补证线索")
                .confidence(0.65d)
                .priorityEvidence(true)
                .build();
        copyNumberArray(item.path("position"), evidence.getPosition());
        copyNumberArray(item.path("bbox"), evidence.getBoundingBox());
        copyNumberArray(item.path("boundingBox"), evidence.getBoundingBox());
        return evidence;
    }

    private List<String> remainingMissing(EvidenceSearchTask task, List<ExtractedEvidence> evidence) {
        List<String> missing = new ArrayList<>(task.getMissingEvidence() == null ? List.of() : task.getMissingEvidence());
        for (ExtractedEvidence item : evidence) {
            String text = (item.getContent() + " " + item.getLayer() + " " + item.getEntityType() + " " + item.getBlockName())
                    .toLowerCase(Locale.ROOT);
            missing.removeIf(value -> text.contains(value.toLowerCase(Locale.ROOT)));
        }
        return missing;
    }

    private String firstMatchedRequirement(EvidenceSearchTask task, JsonNode item) {
        String text = searchableText(item);
        for (String value : allHints(task)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!normalized.isBlank() && text.contains(normalized)) {
                return value;
            }
        }
        return task.getRuleId();
    }

    private List<String> allHints(EvidenceSearchTask task) {
        List<String> result = new ArrayList<>();
        addAll(result, task.getMissingEvidence());
        addAll(result, task.getKeywords());
        addAll(result, task.getLayerHints());
        addAll(result, task.getEntityTypeHints());
        return result;
    }

    private void addAll(List<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                target.add(value);
            }
        }
    }

    private String searchableText(JsonNode item) {
        return item == null ? "" : item.toString().toLowerCase(Locale.ROOT);
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode value = item.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void copyNumberArray(JsonNode source, List<Double> target) {
        if (!source.isArray() || target == null || !target.isEmpty()) {
            return;
        }
        for (JsonNode value : source) {
            if (value.isNumber()) {
                target.add(value.asDouble());
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractionOutput {
        private String taskId;
        private String chunkId;
        private boolean relevant;
        private List<ExtractedEvidence> evidence = new ArrayList<>();
        private List<String> stillMissing = new ArrayList<>();

        private EvidenceExtractionResult toResult(String fallbackTaskId, String fallbackChunkId) {
            return EvidenceExtractionResult.builder()
                    .taskId(taskId == null || taskId.isBlank() ? fallbackTaskId : taskId)
                    .chunkId(chunkId == null || chunkId.isBlank() ? fallbackChunkId : chunkId)
                    .relevant(relevant || evidence != null && !evidence.isEmpty())
                    .evidence(evidence == null ? new ArrayList<>() : evidence)
                    .stillMissing(stillMissing == null ? new ArrayList<>() : stillMissing)
                    .build();
        }
    }
}

package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Mock 单包审核请求体（{@code POST /api/ai/review-mock}）。
 *
 * <p>这是一份"已经准备好可以直接交给 LLM 的审核包"——绕过了 .dxf 解析与
 * Dispatcher 任务拆分阶段，由前端自行组装项目信息、规范条款、证据定位，
 * {@link com.luckycat.cadreview.service.ReviewMockService} 把整个对象 JSON 化后塞进 prompt。
 *
 * <p>典型用途：前端联调审核结果展示组件、提示词与 BeanOutputConverter 的快速验证、给非技术同事走 Demo。
 * 正式的多 Agent 链路请走 {@code /api/review/submit}。
 */
@Data
public class AuditPackage {

    // 指定本次调用的 LLM 供应商；不传时由 LlmProperties.defaultProvider 兜底
    private Provider provider;

    // 项目元数据（图纸类型 / 专业），帮 LLM 在上下文里建立审核场景，必填
    @NotNull
    private ProjectInfo projectInfo;

    // 本次要审核的具体审查项描述，比如"防火门宽度是否满足净宽 0.9m"，必填且不允许空白
    @NotBlank
    private String checkItem;

    // 从图纸里抽取出来的参数键值对（如门宽、层高、材料）；
    // Map 形态便于前端按图纸类型动态扩展字段，键名由前端约定
    @NotNull
    private Map<String, Object> extractedParameters;

    // 对应的规范条款元信息，用于让 LLM 在审核时对齐到具体引用条文
    @NotNull
    private Clause clause;

    // 证据锚点：实体 ID / 图层 / 世界坐标包围盒，用于把审核结论定位到图纸上的具体位置
    @NotNull
    private Evidence evidence;

    /**
     * 项目元数据子结构，描述图纸所处的工程语境。
     */
    @Data
    public static class ProjectInfo {
        // 图纸类型，例如 "建筑平面图" / "结构施工图" / "给排水"
        private String drawingType;
        // 专业分类，例如 "建筑" / "结构" / "暖通"，用于辅助 LLM 切换审核视角
        private String discipline;
    }

    /**
     * 规范条款引用，标识本次审核所对应的具体条文。
     */
    @Data
    public static class Clause {
        // 条款唯一标识，常见形式如 "GB50016-3.4.5"
        private String clauseId;
        // 条款的简要文字概括，给 LLM 直接读，避免自己去猜条款内容
        private String summary;
    }

    /**
     * 证据定位信息，用于把抽出来的参数与图纸位置挂钩。
     */
    @Data
    public static class Evidence {
        // 涉及的 CAD 实体 ID 列表，对应解析后的 IR 中的实体唯一键
        private List<String> entityIds;
        // 实体所在图层名，单个；多图层场景目前由前端选主图层
        private String layer;
        // 世界坐标系下的包围盒，约定顺序 [minX, minY, maxX, maxY]，便于前端高亮
        private List<Double> worldBounds;
    }
}

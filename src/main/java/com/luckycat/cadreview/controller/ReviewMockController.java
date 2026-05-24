package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.AuditPackage;
import com.luckycat.cadreview.dto.ReviewMockResponse;
import com.luckycat.cadreview.service.ReviewMockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock 审核 REST 入口（前端联调专用），路径前缀 {@code /api/ai}。
 *
 * <p><b>这是 Mock 接口</b>：与 {@code ReviewController} 的多 Agent 真实链路完全独立，
 * 不走"上传图纸 → 解析 → Dispatcher → Reviewer → Summarizer"那一整套，
 * 而是直接接收前端已经组装好的 {@link AuditPackage}（一份"审核包"），
 * 经 {@link ReviewMockService} 做一次单点 LLM 调用后返回固定结构的 {@link ReviewMockResponse}。
 *
 * <p>用途：
 * <ul>
 *   <li>前端联调审核结果展示组件时的稳定数据源</li>
 *   <li>单条规则 / 单个条款的结构化输出调试，验证提示词 + BeanOutputConverter 行为</li>
 *   <li>不依赖 CAD 解析器即可演示"AI 审核"效果，方便给非技术同事走 Demo</li>
 * </ul>
 *
 * <p>正式审核请走 {@code /api/review/submit}，不要把本接口当作生产链路使用。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ReviewMockController {

    private final ReviewMockService reviewMockService;

    /**
     * Mock 单条审核：{@code POST /api/ai/review-mock}。
     *
     * <p>请求体为完整的 {@link AuditPackage}，需要前端自行准备齐：
     * <ul>
     *   <li>{@code provider}：可选，指定 LLM 供应商；不传时由 {@link ReviewMockService} 走默认 Provider</li>
     *   <li>{@code projectInfo}：项目元数据（图纸类型、专业）</li>
     *   <li>{@code checkItem}：要审核的具体审查项描述（必填）</li>
     *   <li>{@code extractedParameters}：从图纸里抽出的参数键值对</li>
     *   <li>{@code clause}：对应的规范条款（id + 摘要）</li>
     *   <li>{@code evidence}：定位证据（实体 ID 列表、所在图层、世界坐标包围盒）</li>
     * </ul>
     * 所有字段都会序列化成 JSON 拼进提示词喂给 LLM。
     *
     * <p>返回 {@link ReviewMockResponse}，包含结构化的 findings 列表以及本次调用的 Provider / model / token 用量，
     * 便于前端展示成本。Service 内部带有重试 + 结构化输出解析失败的回退处理，
     * 失败到上限时会抛 {@code StructuredOutputException}，由全局异常处理转成 4xx/5xx。
     *
     * <p>注意：本接口仍然会真的调用 LLM 并产生费用——"Mock"指的是绕过 Agent 编排链路、用假数据驱动，
     * 而不是说不发请求。
     */
    @PostMapping("/review-mock")
    public ApiResult<ReviewMockResponse> reviewMock(@Valid @RequestBody AuditPackage auditPackage) {
        ReviewMockResponse response = reviewMockService.review(auditPackage);
        return ApiResult.ok(response);
    }
}

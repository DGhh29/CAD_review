package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.agent.AgentOrchestrator;
import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewTask;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * CAD 图纸自动审核 REST 入口，路径前缀 {@code /api/review}。
 *
 * <p>整个多 Agent 审核链路（解析 → Dispatcher → Reviewer → Summarizer）的对外门面，
 * 暴露给前端管理后台使用。所有真正的业务流程、超时控制、降级策略都委托给
 * {@link AgentOrchestrator}，本控制器只做 HTTP 入参绑定。
 *
 * <p>该控制器与 {@code ReviewMockController} 是两条独立链路：
 * 这里走真实的 LLM 调用，Mock 那条用于前端联调，不要混用。
 */
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final AgentOrchestrator agentOrchestrator;

    /**
     * 提交一次完整的图纸审核：{@code POST /api/review/submit}。
     *
     * <p>请求格式为 multipart/form-data：
     * <ul>
     *   <li>{@code file}：必填，待审核的图纸文件，仅接受 {@code .dxf} / {@code .dwg}</li>
     *   <li>{@code ruleSet}：可选，逗号分隔的规则 ID 集合（如 {@code "R001,R002"}）；
     *       不传或传 {@code "all"} 时使用 {@link com.luckycat.cadreview.config.AgentProperties}
     *       中全部启用规则</li>
     * </ul>
     *
     * <p>底层走完整链路：CAD 解析 → IR 摘要 → Dispatcher 拆任务 → Reviewer 并行审核 → Summarizer 汇总，
     * 全程共享一个总超时预算；任意环节失败或超时都会降级为带 {@code partial=true} 的 {@link ReviewReport}，
     * 而不是抛错给前端，调用方据此判定是否为"完整结果"。
     *
     * <p>典型场景：用户在前端上传图纸并选好审核规则集后点击"开始审核"，前端调用本接口拿到结构化报告并展示。
     */
    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ReviewReport> submit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "ruleSet", required = false) String ruleSet) {
        return ApiResult.ok(agentOrchestrator.executeReview(file, ruleSet));
    }

    /**
     * 仅执行解析 + Dispatcher 分派，不跑实际审核：{@code POST /api/review/dispatch-only}。
     *
     * <p>参数与 {@link #submit} 完全一致（同 multipart 字段 {@code file} 与查询参数 {@code ruleSet}），
     * 但只调用 {@link AgentOrchestrator#dispatchOnly} 返回 Dispatcher 拆出的任务清单 {@link ReviewTask}，
     * 不会触发 Reviewer / Summarizer，也不会消耗审核侧的 LLM token 预算（只有 Dispatcher 那一次调用）。
     *
     * <p>典型场景：调试规则配置或提示词时，前端用这个接口预览"如果真跑审核会拆出哪些任务、命中哪些规则"，
     * 确认拆分合理后再切到 {@link #submit} 走完整链路。
     */
    @PostMapping(value = "/dispatch-only", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<List<ReviewTask>> dispatchOnly(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "ruleSet", required = false) String ruleSet) {
        return ApiResult.ok(agentOrchestrator.dispatchOnly(file, ruleSet));
    }
}

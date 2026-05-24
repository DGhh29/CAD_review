package com.luckycat.cadreview.common;

import com.luckycat.cadreview.service.ReviewMockService;
import com.luckycat.cadreview.agent.StructuredOutputSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，把后端各层抛出的异常统一转换为 {@link ApiResult} 包裹的 HTTP 响应。
 *
 * <p>所有 {@code @RestController} 抛出的异常都会落到这里，
 * 调用方因此能拿到稳定结构的错误体（{@code {code, msg, data}}），
 * 而不会看到 Spring 默认的堆栈或空白页面。
 *
 * <p>错误码段位约定：
 * <ul>
 *   <li>400xx：客户端请求错误（参数校验失败、非法参数）</li>
 *   <li>500xx：未明确分类的服务端错误</li>
 *   <li>502xx：与下游 LLM 相关的错误（结构化输出解析失败等）</li>
 *   <li>503xx：CAD 解析服务相关错误</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 {@code @Valid} 校验失败抛出的 {@link MethodArgumentNotValidException}。
     *
     * <p>只取第一条字段错误，拼成 {@code "field: message"} 返回；
     * HTTP 400，业务码 {@code 40001}。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("validation error");
        return ResponseEntity.badRequest().body(ApiResult.error(40001, msg));
    }

    /**
     * 处理业务代码主动抛出的 {@link IllegalArgumentException}
     * （如不支持的文件后缀、字段空值校验等）。
     *
     * <p>HTTP 400,业务码 {@code 40002}。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResult.error(40002, ex.getMessage()));
    }

    /**
     * 处理 {@link ReviewMockService} 在 mock 审核流程中
     * 解析 LLM 结构化输出失败的异常。
     *
     * <p>HTTP 502（Bad Gateway，因为问题来自下游 LLM），业务码 {@code 50201}，
     * 同时把 LLM 的原始文本放在 {@code data} 字段里供前端排查。
     */
    @ExceptionHandler(ReviewMockService.StructuredOutputException.class)
    public ResponseEntity<ApiResult<String>> handleStructuredOutput(
            ReviewMockService.StructuredOutputException ex) {
        log.error("Structured output failed", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(50201, "结构化输出解析失败", ex.getRawContent()));
    }

    /**
     * 处理多 Agent 流水线（Dispatcher / Reviewer / Summarizer）
     * 调用 LLM 时结构化输出解析失败的异常。
     *
     * <p>与 {@link #handleStructuredOutput} 类似，但来源是
     * {@link StructuredOutputSupport}，会附带具体出错的 Agent 名（{@code operation}）。
     * HTTP 502，业务码 {@code 50202}，{@code data} 字段携带 LLM 原始文本。
     */
    @ExceptionHandler(StructuredOutputSupport.StructuredOutputException.class)
    public ResponseEntity<ApiResult<String>> handleAgentStructuredOutput(
            StructuredOutputSupport.StructuredOutputException ex) {
        log.error("Agent structured output failed: {}", ex.getOperation(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(50202, "Agent 结构化输出解析失败", ex.getRawContent()));
    }

    /**
     * 处理 CAD 解析阶段（{@code parser} 包）抛出的
     * {@link com.luckycat.cadreview.parser.CadParseException}。
     *
     * <p>来源包括：临时文件落盘失败、外部 HTTP 解析服务连接失败 / 返回 5xx、
     * 响应 JSON 反序列化失败等。HTTP 500，业务码 {@code 50301}。
     */
    @ExceptionHandler(com.luckycat.cadreview.parser.CadParseException.class)
    public ResponseEntity<ApiResult<Void>> handleCadParse(com.luckycat.cadreview.parser.CadParseException ex) {
        log.error("CAD parse failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(50301, ex.getMessage()));
    }

    /**
     * 兜底：所有未被前面分支捕获的 {@link Exception} 都落到这里。
     *
     * <p>不向外暴露原始错误描述，避免泄漏内部实现；只在服务端日志里打全栈。
     * HTTP 500，业务码 {@code 50000}，{@code msg} 固定为 {@code "internal error"}。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(50000, "internal error"));
    }
}

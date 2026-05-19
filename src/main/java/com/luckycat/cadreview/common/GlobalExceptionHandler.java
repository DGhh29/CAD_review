package com.luckycat.cadreview.common;

import com.luckycat.cadreview.service.ReviewMockService;
import com.luckycat.cadreview.agent.StructuredOutputSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("validation error");
        return ResponseEntity.badRequest().body(ApiResult.error(40001, msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResult.error(40002, ex.getMessage()));
    }

    @ExceptionHandler(ReviewMockService.StructuredOutputException.class)
    public ResponseEntity<ApiResult<String>> handleStructuredOutput(
            ReviewMockService.StructuredOutputException ex) {
        log.error("Structured output failed", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(50201, "结构化输出解析失败", ex.getRawContent()));
    }

    @ExceptionHandler(StructuredOutputSupport.StructuredOutputException.class)
    public ResponseEntity<ApiResult<String>> handleAgentStructuredOutput(
            StructuredOutputSupport.StructuredOutputException ex) {
        log.error("Agent structured output failed: {}", ex.getOperation(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(50202, "Agent 结构化输出解析失败", ex.getRawContent()));
    }

    @ExceptionHandler(com.luckycat.cadreview.parser.CadParseException.class)
    public ResponseEntity<ApiResult<Void>> handleCadParse(com.luckycat.cadreview.parser.CadParseException ex) {
        log.error("CAD parse failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(50301, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(50000, "internal error"));
    }
}

package com.luckycat.cadreview.parser;

/**
 * CAD 解析阶段的统一异常类型。
 *
 * <p>{@code parser} 包内任何"解析失败"——包括临时文件落盘失败、
 * 外部 HTTP 解析服务调用失败、响应 JSON 反序列化失败——
 * 都包装成本异常向上抛出，由
 * {@link com.luckycat.cadreview.common.GlobalExceptionHandler#handleCadParse}
 * 统一转成 HTTP 500 / 业务码 50301 的响应。
 *
 * <p>之所以选用 {@link RuntimeException} 而非检查异常，是为了让上游链路
 * （{@code controller}、{@code agent}）保持简洁，不必逐层 {@code throws} 声明。
 */
public class CadParseException extends RuntimeException {
    public CadParseException(String message) {
        super(message);
    }
    public CadParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

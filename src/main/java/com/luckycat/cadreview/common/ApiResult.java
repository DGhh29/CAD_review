package com.luckycat.cadreview.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * REST 接口统一响应包装。
 *
 * <p>所有 {@code @RestController} 返回的 body 都裹一层 {@code ApiResult}，
 * 让前端只需关心 {@code code == 0} 即视为成功，其它情况按 {@code msg} 展示。
 *
 * <p>响应结构：
 * <ul>
 *   <li>{@code code}：业务状态码。{@code 0} 表示成功；非 0 表示失败，
 *       约定按 HTTP-like 区段划分（如 40001/40002 为参数错误，
 *       50000 为未知错误，502xx 为下游/LLM 错误，503xx 为解析器错误）。</li>
 *   <li>{@code msg}：人类可读的提示，成功时固定为 {@code "success"}。</li>
 *   <li>{@code data}：业务负载，类型由调用方泛型参数 {@code T} 决定。</li>
 * </ul>
 *
 * <p>{@link GlobalExceptionHandler} 在捕获各类异常时也会通过 {@code error(...)}
 * 工厂方法生成 {@code ApiResult}，与正常返回保持同构。
 *
 * @param <T> 业务返回数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResult<T> {
    private int code;
    private String msg;
    private T data;

    /**
     * 构造一个成功响应：{@code code=0}，{@code msg="success"}。
     *
     * @param data 业务负载，可为 {@code null}
     */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<T>(0, "success", data);
    }

    /**
     * 构造一个失败响应，仅含错误码与错误描述，{@code data} 为 {@code null}。
     */
    public static <T> ApiResult<T> error(int code, String msg) {
        return new ApiResult<T>(code, msg, null);
    }

    /**
     * 构造一个失败响应，并附带额外的诊断数据。
     *
     * <p>常用于结构化输出解析失败时把 LLM 原始文本回传到 {@code data}，
     * 方便前端调试或人工兜底。
     */
    public static <T> ApiResult<T> error(int code, String msg, T data) {
        return new ApiResult<T>(code, msg, data);
    }
}

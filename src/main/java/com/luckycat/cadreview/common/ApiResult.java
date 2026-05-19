package com.luckycat.cadreview.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResult<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "success", data);
    }

    public static <T> ApiResult<T> error(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }

    public static <T> ApiResult<T> error(int code, String msg, T data) {
        return new ApiResult<>(code, msg, data);
    }
}

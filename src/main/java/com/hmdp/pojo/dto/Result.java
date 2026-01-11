package com.hmdp.pojo.dto;

import lombok.Data;
import lombok.experimental.Accessors;
import java.io.Serializable;

@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

//    状态码
    public static final long SUCCESS = 200;
    public static final long ERROR = 500;
    public static final long UNAUTHORIZED = 401;
    public static final long FORBIDDEN = 403;

    private long code;
    private String message;
    private T data;

    private Result(long code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return new Result<>(SUCCESS,"操作成功",null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS, "操作成功", data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(ERROR, message, null);
    }

    public static <T> Result<T> fail(long code, String message) {
        return new Result<>(code, message, null);
    }
}
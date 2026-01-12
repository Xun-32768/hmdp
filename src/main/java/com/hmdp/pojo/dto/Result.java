package com.hmdp.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok(){
        return new Result(true, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}

//@Data
//@Accessors(chain = true)
//public class Result<T> implements Serializable {
//
//    private static final long serialVersionUID = 1L;
//
////    状态码
//    public static final long SUCCESS = 200;
//    public static final long ERROR = 500;
//    public static final long UNAUTHORIZED = 401;
//    public static final long FORBIDDEN = 403;
//
//    private long code;
//    private String message;
//    private T data;
//
//    private Result(long code, String message, T data) {
//        this.code = code;
//        this.message = message;
//        this.data = data;
//    }
//
//    public static <T> Result<T> success() {
//        return new Result<>(SUCCESS,"操作成功",null);
//    }
//
//    public static <T> Result<T> success(T data) {
//        return new Result<>(SUCCESS, "操作成功", data);
//    }
//
//    public static <T> Result<T> fail(String message) {
//        return new Result<>(ERROR, message, null);
//    }
//
//    public static <T> Result<T> fail(long code, String message) {
//        return new Result<>(code, message, null);
//    }
//}
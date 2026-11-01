package com.joinsoft.common.api.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一 REST 返回对象。
 *
 * <p>字段与 jeecg-boot 的 {@code com.joinsoft.common.api.vo.Result} 保持一致
 * （success / message / code / result / timestamp），便于复用原有前端与业务代码。</p>
 */
@Data
@ApiModel(value = "Result", description = "API接口的返回对象")
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "是否成功", example = "true")
    private boolean success = true;

    @ApiModelProperty(value = "返回的详细说明", example = "成功")
    private String message = "";

    @ApiModelProperty(value = "业务状态码，0 表示成功", example = "0")
    private Integer code = 0;

    @ApiModelProperty(value = "返回的数据")
    private T result;

    @ApiModelProperty(value = "时间戳")
    private long timestamp = System.currentTimeMillis();

    public Result() {
    }

    public Result(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.code = success ? 0 : 500;
    }

    public Result(boolean success, String message, T result) {
        this(success, message);
        this.result = result;
    }

    /** 兼容旧版 base.Result 的 getMsg()/setMsg() 用法。 */
    public String getMsg() {
        return message;
    }

    public void setMsg(String msg) {
        this.message = msg;
    }

    public static <T> Result<T> OK() {
        return new Result<>(true, "成功");
    }

    public static <T> Result<T> OK(T data) {
        return new Result<>(true, "成功", data);
    }

    public static <T> Result<T> OK(String msg) {
        return new Result<>(true, msg);
    }

    public static <T> Result<T> OK(T data, String msg) {
        return new Result<>(true, msg, data);
    }

    public static <T> Result<T> ok() {
        return OK();
    }

    public static <T> Result<T> ok(T data) {
        return OK(data);
    }

    public static <T> Result<T> error(String msg) {
        return new Result<>(false, msg);
    }

    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>(false, msg);
        result.setCode(code);
        return result;
    }

    public static <T> Result<T> error(String msg, T data) {
        return new Result<>(false, msg, data);
    }
}

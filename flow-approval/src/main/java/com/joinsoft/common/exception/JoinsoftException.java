package com.joinsoft.common.exception;

/**
 * 业务异常，兼容原 joinsoft 项目的异常类型。
 */
public class JoinsoftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JoinsoftException(String message) {
        super(message);
    }

    public JoinsoftException(String message, Throwable cause) {
        super(message, cause);
    }
}

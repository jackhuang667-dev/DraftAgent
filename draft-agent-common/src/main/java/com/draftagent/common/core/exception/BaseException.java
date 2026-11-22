package com.draftagent.common.core.exception;

/**
 * 业务异常基类。
 */
public class BaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BaseException(String message) {
        super(message);
        this.code = 500;
    }

    public BaseException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BaseException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

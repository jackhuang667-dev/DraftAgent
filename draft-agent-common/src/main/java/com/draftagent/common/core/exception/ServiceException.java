package com.draftagent.common.core.exception;

/**
 * 业务服务异常。
 */
public class ServiceException extends BaseException {

    private static final long serialVersionUID = 1L;

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(int code, String message) {
        super(code, message);
    }

    public ServiceException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }
}

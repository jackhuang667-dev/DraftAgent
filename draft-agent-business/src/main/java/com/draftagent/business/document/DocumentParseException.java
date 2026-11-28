package com.draftagent.business.document;

/**
 * 文档解析异常（解析失败 / 无可提取文本）。消息应为可直接返回给客户端的脱敏文本，
 * 不得包含本地绝对路径或堆栈。
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

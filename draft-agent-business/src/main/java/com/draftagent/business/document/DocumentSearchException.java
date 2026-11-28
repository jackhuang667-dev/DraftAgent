package com.draftagent.business.document;

/**
 * 向量检索失败（Embedding 不可用/失败、维度错误、Qdrant 检索失败、返回非法等）。
 *
 * <p>消息为脱敏文本，可包含 pointId / 字段名便于排查，但不包含用户问题原文、向量或 API Key。</p>
 */
public class DocumentSearchException extends RuntimeException {

    public DocumentSearchException(String message) {
        super(message);
    }

    public DocumentSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}

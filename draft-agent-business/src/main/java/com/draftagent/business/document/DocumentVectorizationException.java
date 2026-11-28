package com.draftagent.business.document;

/**
 * 文档向量化 / 向量入库失败。消息为脱敏文本（可包含 document_id 便于排查），不包含 API Key。
 */
public class DocumentVectorizationException extends RuntimeException {

    public DocumentVectorizationException(String message) {
        super(message);
    }

    public DocumentVectorizationException(String message, Throwable cause) {
        super(message, cause);
    }
}

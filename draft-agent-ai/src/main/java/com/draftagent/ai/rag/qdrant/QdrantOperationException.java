package com.draftagent.ai.rag.qdrant;

/**
 * Qdrant 操作失败（超时/连接失败/远端错误）。消息为脱敏文本，不含任何凭据。
 */
public class QdrantOperationException extends RuntimeException {

    public QdrantOperationException(String message) {
        super(message);
    }
}

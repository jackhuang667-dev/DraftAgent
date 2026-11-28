package com.draftagent.business.rag;

import com.draftagent.business.document.RetrievedDocumentChunk;

import java.util.List;

/**
 * RAG 生成端口：接收用户问题与已完成权限校验的 Chunk 原文，返回回答及后端生成的引用来源。
 *
 * <p>由 AI 模块实现（复用 DeepSeek ChatClient），business 不依赖 Spring AI。
 * 实现不得让模型生成或覆盖 documentId / chunkId 等来源元数据。</p>
 */
public interface RagAnswerGenerator {

    RagAnswer generate(String query, List<RetrievedDocumentChunk> chunks);
}

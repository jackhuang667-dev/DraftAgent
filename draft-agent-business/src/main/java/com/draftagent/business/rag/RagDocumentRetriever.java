package com.draftagent.business.rag;

import com.draftagent.business.document.DocumentSearchScope;
import com.draftagent.business.document.RetrievedDocumentChunk;

import java.util.List;

/**
 * 已授权 Chunk 原文检索端口（向量检索 + MySQL 回查 + 权限复核）。
 *
 * <p>由 AI 模块的 {@code DocumentRetrievalService} 实现，business 只依赖该接口，
 * 从而让 RAG 编排可以留在 business 层而不反向依赖 ai 模块。</p>
 */
public interface RagDocumentRetriever {

    List<RetrievedDocumentChunk> retrieve(String query, DocumentSearchScope scope);

    List<RetrievedDocumentChunk> retrieve(String query, DocumentSearchScope scope, int topK);
}

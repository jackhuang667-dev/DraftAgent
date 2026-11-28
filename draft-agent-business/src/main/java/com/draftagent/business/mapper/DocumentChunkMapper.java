package com.draftagent.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.draftagent.business.document.DocumentChunkContent;
import com.draftagent.business.entity.DocumentChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 文档分块 Mapper。
 *
 * <p>由启动类 {@code @MapperScan("com.draftagent.**.mapper")} 扫描注册，
 * 无需额外的 {@code @Mapper} 注解。</p>
 */
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 批量回查 READY 文档的 Chunk 原文，并同时按服务端检索范围做权限过滤。
     *
     * <p>租户范围：{@code document.tenant_id = tenantId}；个人范围：
     * {@code document.tenant_id IS NULL AND document.user_id = userId}。
     * 调用方必须保证 chunkIds 非空（空列表不允许生成 {@code IN ()}）。</p>
     */
    @Select("""
            <script>
            SELECT d.document_id AS document_id,
                   c.document_pk AS document_pk,
                   c.document_id AS chunk_document_id,
                   c.chunk_id    AS chunk_id,
                   c.chunk_index AS chunk_index,
                   c.content     AS content
            FROM document_chunk c
            INNER JOIN document d ON d.document_pk = c.document_pk
            WHERE c.chunk_id IN
            <foreach collection="chunkIds" item="chunkId" open="(" separator="," close=")">
                #{chunkId}
            </foreach>
              AND d.status = #{readyStatus}
            <choose>
                <when test="tenantId != null">
                    AND d.tenant_id = #{tenantId}
                </when>
                <otherwise>
                    AND d.tenant_id IS NULL
                    AND d.user_id = #{userId}
                </otherwise>
            </choose>
            </script>
            """)
    List<DocumentChunkContent> selectReadyChunkContents(@Param("chunkIds") List<Long> chunkIds,
                                                        @Param("userId") Long userId,
                                                        @Param("tenantId") Long tenantId,
                                                        @Param("readyStatus") String readyStatus);
}


package com.draftagent.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.draftagent.business.entity.Document;

/**
 * RAG 文档元数据 Mapper。
 *
 * <p>由启动类 {@code @MapperScan("com.draftagent.**.mapper")} 扫描注册，
 * 无需额外的 {@code @Mapper} 注解。</p>
 */
public interface DocumentMapper extends BaseMapper<Document> {
}

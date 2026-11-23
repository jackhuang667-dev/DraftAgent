package com.draftagent.business.service;

import com.draftagent.business.annotation.LogRecord;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.mapper.DraftMapper;
import com.draftagent.common.core.exception.ServiceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class DraftService {

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int CONTENT_MAX_LENGTH = 10000;

    private final DraftMapper draftMapper;

    public DraftService(DraftMapper draftMapper) {
        this.draftMapper = draftMapper;
    }

    @LogRecord("提交草案")
    public void submitDraft() {
        System.out.println("正在提交草案...");
    }

    @LogRecord("评审草案")
    public void reviewDraft() {
        System.out.println("正在评审草案...");
    }

    public void queryDraft() {
        System.out.println("正在查询草案...");
    }

    /**
     * 创建草案，初始状态为 {@link Draft#STATUS_DRAFT}，持久化到数据库。
     */
    @Transactional
    public Draft createDraft(String title, String content) {
        if (!StringUtils.hasText(title)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "草案标题不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new ServiceException(STATUS_BAD_REQUEST, "草案内容不能为空");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.length() > TITLE_MAX_LENGTH) {
            throw new ServiceException(STATUS_BAD_REQUEST, "草案标题长度不能超过" + TITLE_MAX_LENGTH + "个字符");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new ServiceException(STATUS_BAD_REQUEST, "草案内容长度不能超过" + CONTENT_MAX_LENGTH + "个字符");
        }

        LocalDateTime now = LocalDateTime.now();
        Draft draft = new Draft();
        draft.setTitle(trimmedTitle);
        draft.setContent(content);
        draft.setStatus(Draft.STATUS_DRAFT);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        try {
            draftMapper.insert(draft);
        } catch (DataAccessException e) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "数据库连接失败，暂无法创建草案", e);
        }
        return draft;
    }

    /**
     * 按草案ID查询草案状态（只读）。
     *
     * <p>存在返回真实状态，不存在返回 {@code found=false}；
     * 数据库连接失败则以 503 业务异常区分，不复用「未找到」语义。</p>
     */
    public DraftStatusView queryDraftStatus(Long draftId) {
        if (draftId == null) {
            return new DraftStatusView(null, false, null, "草案ID不能为空");
        }
        Draft draft;
        try {
            draft = draftMapper.selectById(draftId);
        } catch (DataAccessException e) {
            throw new ServiceException(STATUS_SERVICE_UNAVAILABLE, "数据库连接失败，暂无法查询草案状态", e);
        }
        if (draft == null) {
            return new DraftStatusView(draftId, false, null, "未找到指定草案");
        }
        return new DraftStatusView(draftId, true, draft.getStatus(), "查询成功");
    }
}

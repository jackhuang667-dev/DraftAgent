package com.draftagent.business.dto;

/**
 * 草案状态只读视图。
 *
 * <p>{@code found=false} 表示未找到对应草案，此时 {@code status} 为 null；
 * 若查询过程发生数据库连接失败，将由业务异常区分（返回 503），不会以本视图表达。</p>
 */
public class DraftStatusView {

    private Long draftId;

    private boolean found;

    private String status;

    private String message;

    public DraftStatusView() {
    }

    public DraftStatusView(Long draftId, boolean found, String status, String message) {
        this.draftId = draftId;
        this.found = found;
        this.status = status;
        this.message = message;
    }

    public Long getDraftId() {
        return draftId;
    }

    public void setDraftId(Long draftId) {
        this.draftId = draftId;
    }

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

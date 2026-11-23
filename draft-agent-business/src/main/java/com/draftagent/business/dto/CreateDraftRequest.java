package com.draftagent.business.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建草案请求。
 */
public class CreateDraftRequest {

    @NotBlank(message = "草案标题不能为空")
    @Size(max = 200, message = "草案标题长度不能超过200个字符")
    private String title;

    @NotBlank(message = "草案内容不能为空")
    @Size(max = 10000, message = "草案内容长度不能超过10000个字符")
    private String content;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}

package com.draftagent.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 会话消息实体，对应数据库表 {@code conversation_message}。
 *
 * <p>{@code conversationPk} 关联 {@link Conversation#getId()}（内部主键），不是业务 UUID。</p>
 */
@TableName("conversation_message")
public class ConversationMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_pk")
    private Long conversationPk;

    @TableField("turn_id")
    private String turnId;

    @TableField("sequence_no")
    private Long sequenceNo;

    @TableField("role")
    private String role;

    @TableField("message_type")
    private String messageType;

    @TableField("message_status")
    private String messageStatus;

    @TableField("content")
    private String content;

    @TableField("error_code")
    private String errorCode;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationPk() {
        return conversationPk;
    }

    public void setConversationPk(Long conversationPk) {
        this.conversationPk = conversationPk;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getMessageStatus() {
        return messageStatus;
    }

    public void setMessageStatus(String messageStatus) {
        this.messageStatus = messageStatus;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

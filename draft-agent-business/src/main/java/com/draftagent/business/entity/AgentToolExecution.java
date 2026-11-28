package com.draftagent.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Agent 工具执行记录实体，对应数据库表 {@code agent_tool_execution}。
 *
 * <p>{@code conversationPk} 关联 {@link Conversation#getId()}（内部主键），不是业务 UUID。
 * {@code arguments} 先以 {@code String} 映射数据库 JSON 字段；{@code resultRef} 保存结果引用
 * （如 draftId），避免与具体工具的返回结构耦合。</p>
 */
@TableName("agent_tool_execution")
public class AgentToolExecution {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("execution_id")
    private String executionId;

    @TableField("conversation_pk")
    private Long conversationPk;

    @TableField("turn_id")
    private String turnId;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("tool_call_id")
    private String toolCallId;

    @TableField("tool_name")
    private String toolName;

    @TableField("arguments")
    private String arguments;

    @TableField("status")
    private String status;

    @TableField("result_ref")
    private String resultRef;

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

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultRef() {
        return resultRef;
    }

    public void setResultRef(String resultRef) {
        this.resultRef = resultRef;
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

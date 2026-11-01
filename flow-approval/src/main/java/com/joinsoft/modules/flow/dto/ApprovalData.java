package com.joinsoft.modules.flow.dto;

import lombok.Data;

import java.util.Map;

/**
 * 审批操作入参，字段与默认值语义与原项目保持一致。
 */
@Data
public class ApprovalData {

    /** 任务ID */
    private String taskId;

    /** 自定义标志，可以通过此标志判断流程走向 */
    private Long flag;

    /** 审批意见 */
    private String comment;

    /** 流程跳转用：目标节点定义 key */
    private String targetTaskDefinitionKey;

    /** 流程跳转用：目标节点审核人，为空时按流程定义解析 */
    private String assignee;

    /** 传入流程中的变量 */
    private Map<String, Object> variables;

    public Long getFlag() {
        return flag == null ? 1L : flag;
    }

    public String getComment(String defaultComment) {
        if (comment == null || comment.trim().isEmpty()) {
            return defaultComment != null && !defaultComment.trim().isEmpty() ? defaultComment : "审批通过";
        }
        return comment;
    }

    public String getComment() {
        if (comment == null || comment.trim().isEmpty()) {
            return "审批通过";
        }
        return comment;
    }
}

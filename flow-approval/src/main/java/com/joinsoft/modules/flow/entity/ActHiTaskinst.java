package com.joinsoft.modules.flow.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;

/**
 * 审批历史行，兼容原 ACT_HI_TASKINST 返回结构。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActHiTaskinst implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String procDefId;

    private String taskDefKey;

    private String procInstId;

    private String executionId;

    private String name;

    private String parentTaskId;

    private String description;

    private String owner;

    private String assignee;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Timestamp startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Timestamp claimTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Timestamp endTime;

    private Long duration;

    private String deleteReason;

    private Integer priority;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Timestamp dueDate;

    private String formKey;

    private String category;

    private String tenantId;

    @ApiModelProperty(value = "审批意见")
    private String comment;

    private String processDefinitionName;

    private String assigneeName;

    private String assigneeAvatar;

    private String businessKey;

    private String url;

    private String summary;

    private String currentTaskName;

    private String tableId;

    private String title;

    private String internalUrl;

    private String editUrl;

    private String startUserId;

    private String meetSignature;

    private String nextTaskId;

    private Map<String, Object> variables;

    private String taskFlow;

    private String busiId;

    /** 令牌/活动类型，便于前端区分节点。 */
    private String activityType;

    public void setStartTime(Date date) {
        this.startTime = date == null ? null : new Timestamp(date.getTime());
    }

    public void setEndTime(Date date) {
        this.endTime = date == null ? null : new Timestamp(date.getTime());
    }
}

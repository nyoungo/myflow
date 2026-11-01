package com.joinsoft.modules.flow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 流程任务（合并原 ACT_RU_TASK / ACT_HI_TASKINST / identity link）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("flow_task")
public class FlowTask implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELED = "CANCELED";

    public static final String DELEGATION_PENDING = "PENDING";
    public static final String DELEGATION_RESOLVED = "RESOLVED";

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("inst_id")
    private String instId;

    @TableField("def_id")
    private String defId;

    @TableField("task_def_key")
    private String taskDefKey;

    @TableField("name")
    private String name;

    @TableField("assignee")
    private String assignee;

    /** 候选人，CSV，形如 {@code ,zhangsan,lisi,} */
    @TableField("candidate_users")
    private String candidateUsers;

    /** 候选角色编码，CSV，形如 {@code ,manager,} */
    @TableField("candidate_groups")
    private String candidateGroups;

    @TableField("owner")
    private String owner;

    @TableField("delegation_state")
    private String delegationState;

    @TableField("form_key")
    private String formKey;

    @TableField("priority")
    private Integer priority;

    @TableField("status")
    private String status;

    /** 对应流程实例中的令牌ID，用于并行分支推进 */
    @TableField("token_id")
    private String tokenId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("start_time")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("claim_time")
    private Date claimTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("end_time")
    private Date endTime;

    @TableField("duration")
    private Long duration;

    @TableField("delete_reason")
    private String deleteReason;

    @TableField("parent_task_id")
    private String parentTaskId;

    @TableField("tenant_id")
    private String tenantId;
}

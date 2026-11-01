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
 * 审批记录 / 流程意见（合并原 ACT_HI_COMMENT 与 flow_state）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("flow_comment")
public class FlowComment implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_COMMENT = "comment";
    public static final String TYPE_BACK = "back";
    public static final String TYPE_JUMP = "jump";
    public static final String TYPE_CANCEL = "cancel";
    public static final String TYPE_TERMINAL = "terminal";
    public static final String TYPE_SYSTEM = "system";

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("inst_id")
    private String instId;

    @TableField("task_id")
    private String taskId;

    @TableField("task_def_key")
    private String taskDefKey;

    @TableField("task_name")
    private String taskName;

    @TableField("comment_type")
    private String commentType;

    @TableField("user_id")
    private String userId;

    @TableField("user_name")
    private String userName;

    @TableField("message")
    private String message;

    @TableField("flow_state")
    private String flowState;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("create_time")
    private Date createTime;
}

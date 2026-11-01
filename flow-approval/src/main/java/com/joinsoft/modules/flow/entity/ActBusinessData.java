package com.joinsoft.modules.flow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 工作流实例 + 业务主表（合并原 ACT_RU_EXECUTION / ACT_HI_PROCINST / act_business_data）。
 *
 * <p>保留原 ActBusinessData 的字段命名，业务代码可继续使用
 * {@code IActBusinessDataService} 与 {@code FlowUtil.handlePageList}。</p>
 */
@Data
@TableName("flow_inst")
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@ApiModel(value = "ActBusinessData", description = "工作流实例与业务主表")
public class ActBusinessData implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，同时作为流程实例ID */
    @TableId(type = IdType.ASSIGN_ID, value = "id")
    @ApiModelProperty(value = "主键")
    private String id;

    /** 流程实例ID（与 id 相同，兼容原字段） */
    @TableField("proc_inst_id")
    @ApiModelProperty(value = "流程实例id")
    private String procInstId;

    @TableField("business_id")
    @ApiModelProperty(value = "业务id")
    private String businessId;

    @TableField("business_key")
    @ApiModelProperty(value = "业务key")
    private String businessKey;

    @TableField("task_id")
    @ApiModelProperty(value = "当前任务id")
    private String taskId;

    @TableField("task_name")
    @ApiModelProperty(value = "当前任务名")
    private String taskName;

    @TableField("start_user_id")
    @ApiModelProperty(value = "流程发起人")
    private String startUserId;

    @TableField("start_user_name")
    @ApiModelProperty(value = "流程发起人姓名")
    private String startUserName;

    @TableField("start_depart")
    @ApiModelProperty(value = "发起人部门")
    private String startDepart;

    @TableField("start_role")
    @ApiModelProperty(value = "发起人角色")
    private String startRole;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("start_time")
    @ApiModelProperty(value = "流程发起时间")
    private Date startTime;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("end_time")
    @ApiModelProperty(value = "流程结束时间")
    private Date endTime;

    @TableField("title")
    @ApiModelProperty(value = "流程标题")
    private String title;

    @TableField("state")
    @ApiModelProperty(value = "流程状态：1 运行中，2 已完成，3 已终止，4 已撤销")
    private String state;

    @TableField("tenant_id")
    @ApiModelProperty(value = "租户id")
    private String tenantId;

    @TableField("ext_id")
    @ApiModelProperty(value = "扩展字段")
    private String extId;

    @TableField("def_id")
    @ApiModelProperty(value = "流程定义ID")
    private String defId;

    @TableField("def_key")
    @ApiModelProperty(value = "流程定义 key")
    private String defKey;

    @TableField("def_name")
    @ApiModelProperty(value = "流程定义名称")
    private String defName;

    @TableField("url")
    @ApiModelProperty(value = "业务详情地址")
    private String url;

    @TableField("variables")
    @ApiModelProperty(value = "流程变量 JSON")
    private String variables;

    @TableField("active_tokens")
    @ApiModelProperty(value = "活动令牌 JSON")
    private String activeTokens;

    @TableField("activities")
    @ApiModelProperty(value = "已执行节点 JSON")
    private String activities;

    /** 是否运行中 */
    public boolean isRunning() {
        return "1".equals(state);
    }
}

package com.joinsoft.modules.flow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 已发布流程定义视图，兼容原 ACT_RE_PROCDEF 的返回字段。
 *
 * <p>数据来源为 {@code flow_def} 中 {@code version>=1} 的记录。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("flow_def")
public class ActReProcdefEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "流程定义ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("rev")
    private Integer rev;

    @ApiModelProperty(value = "流程分类")
    @TableField("category")
    private String category;

    @ApiModelProperty(value = "流程名称")
    @TableField("def_name")
    private String name;

    @ApiModelProperty(value = "流程定义 key")
    @TableField("def_key")
    private String key;

    @ApiModelProperty(value = "流程版本号")
    @TableField("version")
    private Integer version;

    @ApiModelProperty(value = "部署编号")
    @TableField("deployment_id")
    private String deploymentId;

    @ApiModelProperty(value = "描述信息")
    @TableField("description")
    private String description;

    @ApiModelProperty(value = "所属租户")
    @TableField("tenant_id")
    private String tenantId;

    @ApiModelProperty(value = "流程状态：DRAFT / PUBLISHED / SUSPENDED")
    @TableField("status")
    private String status;

    @ApiModelProperty(value = "BPMN XML 资源")
    @TableField("bpmn_xml")
    private String bpmnXml;

    @ApiModelProperty(value = "SVG 资源")
    @TableField("svg_xml")
    private String svgXml;

    @ApiModelProperty(value = "部署时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("deploy_time")
    private Date deployTime;

    @TableField(exist = false)
    private String resourceName;

    @TableField(exist = false)
    private String dgrmResourceName;

    @TableField(exist = false)
    private String hasStartFormKey;

    @TableField(exist = false)
    private String hasGraphicalNotation;

    @TableField(exist = false)
    private String engineVersion;

    @TableField(exist = false)
    private byte[] base64;

    /** 兼容原字段：1 激活，2 挂起。 */
    public Integer getSuspensionState() {
        return "SUSPENDED".equals(status) ? 2 : 1;
    }

    public void setSuspensionState(Integer suspensionState) {
        if (suspensionState != null) {
            this.status = suspensionState == 2 ? "SUSPENDED" : "PUBLISHED";
        }
    }
}

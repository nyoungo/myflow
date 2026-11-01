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
 * 流程定义 / 流程模板。
 *
 * <p>合并原 ACT_RE_MODEL 与 ACT_RE_PROCDEF：{@code version=0} 表示可编辑草稿，
 * {@code version>=1} 表示已发布的不可变快照。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("flow_def")
public class ActReModelEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @ApiModelProperty(value = "乐观锁版本号")
    @TableField("rev")
    private Integer rev;

    @ApiModelProperty(value = "模型的名称")
    @TableField("def_name")
    private String name;

    @ApiModelProperty(value = "模型的关键字（流程定义 key）")
    @TableField("def_key")
    private String key;

    @ApiModelProperty(value = "类型/分组")
    @TableField("category")
    private String category;

    @ApiModelProperty(value = "描述信息")
    @TableField("description")
    private String description;

    @ApiModelProperty(value = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("create_time")
    private Date createTime;

    @ApiModelProperty(value = "最后修改时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("update_time")
    private Date lastUpdateTime;

    @ApiModelProperty(value = "版本，0 表示草稿，从 1 开始为已发布版本")
    @TableField("version")
    private Integer version;

    @ApiModelProperty(value = "数据元信息，json 格式")
    @TableField("meta_info")
    private String metaInfo;

    @ApiModelProperty(value = "部署ID")
    @TableField("deployment_id")
    private String deploymentId;

    @ApiModelProperty(value = "租户ID")
    @TableField("tenant_id")
    private String tenantId;

    @ApiModelProperty(value = "流程状态：DRAFT / PUBLISHED / SUSPENDED")
    @TableField("status")
    private String status;

    @ApiModelProperty(value = "BPMN 2.0 XML")
    @TableField("bpmn_xml")
    private String bpmnXml;

    @ApiModelProperty(value = "流程图 SVG")
    @TableField("svg_xml")
    private String svgXml;

    @ApiModelProperty(value = "发布时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("deploy_time")
    private Date deployTime;

    /** 兼容原 ACT_RE_MODEL 字段，新表结构不再使用。 */
    @TableField(exist = false)
    private String editorSourceValueId;

    /** 兼容原 ACT_RE_MODEL 字段，新表结构不再使用。 */
    @TableField(exist = false)
    private String editorSourceExtraValueId;

    /** 实例数量，列表附加信息。 */
    @TableField(exist = false)
    private Long instanceCount;
}

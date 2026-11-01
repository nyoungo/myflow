package com.joinsoft.modules.flow.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 运行中流程实例视图，兼容原 ActRuExecution 返回字段。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActRuExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String procInstId;

    private String businessKey;

    private String businessId;

    private String procDefId;

    private String defKey;

    private String name;

    private String title;

    private Integer suspensionState;

    private String state;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    private String startUserId;

    private String startUserName;

    private String taskName;

    private String taskId;

    private String tenantId;

    private String url;
}

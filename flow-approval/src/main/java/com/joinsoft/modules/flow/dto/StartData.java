package com.joinsoft.modules.flow.dto;

import lombok.Data;

import java.util.Map;

/**
 * 启动流程入参，字段与原项目保持一致。
 */
@Data
public class StartData {

    private String workflowId;

    private String businessId;

    private String url;

    private String title;

    private Map<String, Object> variables;
}

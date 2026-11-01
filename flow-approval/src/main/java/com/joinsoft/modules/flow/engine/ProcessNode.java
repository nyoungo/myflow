package com.joinsoft.modules.flow.engine;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BPMN 节点定义（轻量模型，不含 Activiti 依赖）。
 */
@Data
public class ProcessNode {

    public static final String START = "startEvent";
    public static final String END = "endEvent";
    public static final String TERMINATE_END = "terminateEndEvent";
    public static final String USER_TASK = "userTask";
    public static final String EXCLUSIVE_GATEWAY = "exclusiveGateway";
    public static final String PARALLEL_GATEWAY = "parallelGateway";
    public static final String INCLUSIVE_GATEWAY = "inclusiveGateway";
    public static final String SERVICE_TASK = "serviceTask";
    public static final String SCRIPT_TASK = "scriptTask";
    public static final String OTHER = "other";

    private String id;
    private String name;
    private String type;

    private String assignee;
    private List<String> candidateUsers = new ArrayList<>();
    private List<String> candidateGroups = new ArrayList<>();
    private List<String> userFilters = new ArrayList<>();
    private boolean autoPass;
    private boolean noticeStartUser;
    private boolean noticeApprovalUser;

    private String formKey;
    private String documentation;
    private String defaultFlowId;

    private List<String> incoming = new ArrayList<>();
    private List<String> outgoing = new ArrayList<>();

    /** 原始扩展属性，便于调试与前端展示。 */
    private Map<String, String> extensionElements = new LinkedHashMap<>();

    public boolean isUserTask() {
        return USER_TASK.equals(type);
    }

    public boolean isEnd() {
        return END.equals(type) || TERMINATE_END.equals(type);
    }
}

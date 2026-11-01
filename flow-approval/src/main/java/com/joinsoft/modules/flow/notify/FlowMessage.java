package com.joinsoft.modules.flow.notify;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 流程通知消息。
 */
@Data
public class FlowMessage {

    public static final String TYPE_TODO = "TODO";
    public static final String TYPE_CC = "CC";
    public static final String TYPE_URGE = "URGE";
    public static final String TYPE_RESULT = "RESULT";

    private String type;
    private String title;
    private String content;
    private List<String> userIds = new ArrayList<>();
    private String instId;
    private String taskId;
    private String businessId;
    private String defKey;
}

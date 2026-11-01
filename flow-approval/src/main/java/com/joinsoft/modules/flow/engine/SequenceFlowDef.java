package com.joinsoft.modules.flow.engine;

import lombok.Data;

/**
 * 顺序流定义。
 */
@Data
public class SequenceFlowDef {

    private String id;
    private String name;
    private String sourceRef;
    private String targetRef;
    private String conditionExpression;
}

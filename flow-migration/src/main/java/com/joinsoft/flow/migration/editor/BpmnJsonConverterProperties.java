package com.joinsoft.flow.migration.editor;

import org.activiti.bpmn.model.BaseElement;
import org.activiti.editor.language.json.converter.BaseBpmnJsonConverter;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;

import java.util.Map;

/**
 * 暴露 Activiti {@link BpmnJsonConverter} 内部的转换器注册表。
 *
 * <p>旧项目用同样的做法把 {@link CustomUserTaskJsonConverter} 注册进去，
 * 从而在 JSON <-> BPMN 转换时保留 userFilter/autoPass 等扩展属性。</p>
 */
public class BpmnJsonConverterProperties extends BpmnJsonConverter {

    public static Map<Class<? extends BaseElement>, Class<? extends BaseBpmnJsonConverter>> getConvertersToJsonMap() {
        return convertersToJsonMap;
    }

    public static Map<String, Class<? extends BaseBpmnJsonConverter>> getConvertersToBpmnMap() {
        return convertersToBpmnMap;
    }
}

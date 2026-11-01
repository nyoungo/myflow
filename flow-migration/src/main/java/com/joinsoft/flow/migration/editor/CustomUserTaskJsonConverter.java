package com.joinsoft.flow.migration.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.activiti.bpmn.model.BaseElement;
import org.activiti.bpmn.model.CustomProperty;
import org.activiti.bpmn.model.ExtensionElement;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.UserTask;
import org.activiti.editor.constants.StencilConstants;
import org.activiti.editor.language.json.converter.BaseBpmnJsonConverter;
import org.activiti.editor.language.json.converter.UserTaskJsonConverter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 复制自旧项目 com.joinsoft.modules.flow.utils.CustomUserTaskJsonConverter。
 *
 * <p>Activiti 自带的 UserTaskJsonConverter 不认识旧设计器里的自定义属性，
 * 直接转换会丢掉 userFilter、approveButton、autoPass、noticeStartUser 等配置。
 * 这里在标准转换之上补齐这些属性，再注册回 {@link BpmnJsonConverterProperties}。</p>
 */
public class CustomUserTaskJsonConverter extends UserTaskJsonConverter implements StencilConstants {

    /** 旧设计器在 UserTask 上保存的自定义属性。 */
    private static final String[] CUSTOM_PROPERTIES = {
            "userFilter",
            "approveButton",
            "approveButtonNames",
            "autoPass",
            "noticeStartUser",
            "noticeApprovalUser"
    };

    @Override
    protected void convertElementToJson(ObjectNode propertiesNode, BaseElement baseElement) {
        super.convertElementToJson(propertiesNode, baseElement);
        UserTask userTask = (UserTask) baseElement;

        Map<String, List<ExtensionElement>> customProperties = userTask.getExtensionElements();
        if (customProperties != null) {
            for (String key : CUSTOM_PROPERTIES) {
                setKey(customProperties, propertiesNode, key);
            }
        }
    }

    private void setKey(Map<String, List<ExtensionElement>> customProperties,
                        ObjectNode propertiesNode, String key) {
        if (customProperties.containsKey(key) && !customProperties.get(key).isEmpty()) {
            ExtensionElement element = customProperties.get(key).get(0);
            setPropertyValue(key, element.getElementText(), propertiesNode);
        }
    }

    @Override
    protected FlowElement convertJsonToElement(JsonNode elementNode, JsonNode modelNode,
                                              Map<String, JsonNode> shapeMap) {
        FlowElement flowElement = super.convertJsonToElement(elementNode, modelNode, shapeMap);
        if (!(flowElement instanceof UserTask)) {
            return flowElement;
        }
        UserTask userTask = (UserTask) flowElement;
        List<CustomProperty> customPropertyList = new ArrayList<CustomProperty>();
        for (String key : CUSTOM_PROPERTIES) {
            buildCustomProperty(elementNode, customPropertyList, key);
        }
        userTask.setCustomProperties(customPropertyList);
        return userTask;
    }

    private void buildCustomProperty(JsonNode elementNode, List<CustomProperty> customPropertyList, String key) {
        String value = getPropertyValueAsString(key, elementNode);
        if (StringUtils.isNotBlank(value)) {
            CustomProperty customProperty = new CustomProperty();
            customProperty.setName(key);
            customProperty.setSimpleValue(value);
            customPropertyList.add(customProperty);
        }
    }

    public static void fillTypes(
            Map<String, Class<? extends BaseBpmnJsonConverter>> convertersToBpmnMap,
            Map<Class<? extends BaseElement>, Class<? extends BaseBpmnJsonConverter>> convertersToJsonMap) {

        fillJsonTypes(convertersToBpmnMap);
        fillBpmnTypes(convertersToJsonMap);
    }

    public static void fillJsonTypes(Map<String, Class<? extends BaseBpmnJsonConverter>> convertersToBpmnMap) {
        convertersToBpmnMap.put(STENCIL_TASK_USER, CustomUserTaskJsonConverter.class);
    }

    public static void fillBpmnTypes(
            Map<Class<? extends BaseElement>, Class<? extends BaseBpmnJsonConverter>> convertersToJsonMap) {
        convertersToJsonMap.put(UserTask.class, CustomUserTaskJsonConverter.class);
    }
}

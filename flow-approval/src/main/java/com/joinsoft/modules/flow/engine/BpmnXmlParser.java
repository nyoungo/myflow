package com.joinsoft.modules.flow.engine;

import com.joinsoft.common.exception.JoinsoftException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 自研 BPMN 2.0 解析器，仅提取流程执行需要的节点、顺序流与扩展属性。
 */
@Slf4j
@Component
public class BpmnXmlParser {

    /** 与前端 flow-moddle 扩展保持一致的命名空间。 */
    public static final String FLOW_NAMESPACE = "http://joinsoft.com/flow";

    public ProcessModel parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new JoinsoftException("BPMN 内容为空");
        }
        Element processElement = readProcessElement(xml);
        ProcessModel model = new ProcessModel();
        model.setId(attr(processElement, "id"));
        model.setName(attr(processElement, "name"));
        model.setCategory(attr(processElement, "category"));

        NodeList children = processElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) {
                continue;
            }
            Element element = (Element) child;
            String localName = localName(element);
            if ("sequenceFlow".equals(localName)) {
                SequenceFlowDef flow = readFlow(element);
                model.addFlow(flow);
            } else if ("extensionElements".equals(localName) || "documentation".equals(localName)
                    || "laneSet".equals(localName) || "ioSpecification".equals(localName)) {
                // 流程级信息，忽略
            } else {
                ProcessNode node = readNode(element, localName);
                if (node != null) {
                    model.addNode(node);
                }
            }
        }

        model.rebuildRelations();

        if (model.getStartEventId() == null) {
            model.setStartEventId(model.getNodes().values().stream()
                    .filter(node -> ProcessNode.START.equals(node.getType()))
                    .map(ProcessNode::getId)
                    .findFirst().orElse(null));
        }
        if (model.getStartEventId() == null) {
            throw new JoinsoftException("流程缺少开始节点");
        }
        model.setEndEventIds(model.getNodes().values().stream()
                .filter(ProcessNode::isEnd)
                .map(ProcessNode::getId)
                .collect(Collectors.toList()));
        return model;
    }

    private Element readProcessElement(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList processes = document.getElementsByTagNameNS("*", "process");
            if (processes.getLength() == 0) {
                processes = document.getElementsByTagName("process");
            }
            if (processes.getLength() == 0) {
                throw new JoinsoftException("BPMN 中未找到 process 定义");
            }
            Element process = (Element) processes.item(0);
            return process;
        } catch (JoinsoftException e) {
            throw e;
        } catch (Exception e) {
            throw new JoinsoftException("BPMN 解析失败: " + e.getMessage(), e);
        }
    }

    private ProcessNode readNode(Element element, String localName) {
        ProcessNode node = new ProcessNode();
        node.setId(attr(element, "id"));
        node.setName(attr(element, "name"));
        if (node.getId() == null || node.getId().isEmpty()) {
            return null;
        }
        node.setDefaultFlowId(attr(element, "default"));
        node.setAssignee(attr(element, "assignee"));
        node.setCandidateUsers(splitCsv(attr(element, "candidateUsers")));
        node.setCandidateGroups(splitCsv(attr(element, "candidateGroups")));
        node.setFormKey(attr(element, "formKey"));

        switch (localName) {
            case "startEvent":
                node.setType(ProcessNode.START);
                break;
            case "endEvent":
                node.setType(hasChild(element, "terminateEventDefinition")
                        ? ProcessNode.TERMINATE_END : ProcessNode.END);
                break;
            case "userTask":
                node.setType(ProcessNode.USER_TASK);
                break;
            case "exclusiveGateway":
                node.setType(ProcessNode.EXCLUSIVE_GATEWAY);
                break;
            case "parallelGateway":
                node.setType(ProcessNode.PARALLEL_GATEWAY);
                break;
            case "inclusiveGateway":
                node.setType(ProcessNode.INCLUSIVE_GATEWAY);
                break;
            case "serviceTask":
            case "scriptTask":
            case "manualTask":
            case "businessRuleTask":
            case "sendTask":
            case "receiveTask":
                node.setType(ProcessNode.OTHER);
                break;
            case "boundaryEvent":
            case "subProcess":
            case "callActivity":
                node.setType(ProcessNode.OTHER);
                break;
            default:
                // 忽略 diagram / 未知节点
                if ("sequenceFlow".equals(localName)) {
                    return null;
                }
                node.setType(ProcessNode.OTHER);
                break;
        }

        readDocumentation(element, node);
        readExtensionElements(element, node);
        return node;
    }

    private SequenceFlowDef readFlow(Element element) {
        SequenceFlowDef flow = new SequenceFlowDef();
        flow.setId(attr(element, "id"));
        flow.setName(attr(element, "name"));
        flow.setSourceRef(attr(element, "sourceRef"));
        flow.setTargetRef(attr(element, "targetRef"));
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "conditionExpression".equals(localName((Element) child))) {
                String text = child.getTextContent();
                flow.setConditionExpression(text == null ? null : text.trim());
            }
        }
        return flow;
    }

    private void readDocumentation(Element element, ProcessNode node) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "documentation".equals(localName((Element) child))) {
                node.setDocumentation(child.getTextContent() == null ? null : child.getTextContent().trim());
            }
        }
    }

    private void readExtensionElements(Element element, ProcessNode node) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element) || !"extensionElements".equals(localName((Element) child))) {
                continue;
            }
            NodeList extensions = child.getChildNodes();
            for (int j = 0; j < extensions.getLength(); j++) {
                Node extensionNode = extensions.item(j);
                if (!(extensionNode instanceof Element)) {
                    continue;
                }
                Element extension = (Element) extensionNode;
                String key = localName(extension);
                String value = extension.getTextContent();
                if (value == null || value.trim().isEmpty()) {
                    value = firstNonEmpty(attr(extension, "stringValue"), attr(extension, "value"),
                            attr(extension, "name"));
                }
                if (value == null) {
                    value = "";
                }
                value = value.trim();
                node.getExtensionElements().put(key, value);
                if ("userFilter".equalsIgnoreCase(key)) {
                    node.setUserFilters(splitCsv(value));
                } else if ("autoPass".equalsIgnoreCase(key)) {
                    node.setAutoPass("true".equalsIgnoreCase(value));
                } else if ("noticeStartUser".equalsIgnoreCase(key)) {
                    node.setNoticeStartUser("true".equalsIgnoreCase(value));
                } else if ("noticeApprovalUser".equalsIgnoreCase(key)) {
                    node.setNoticeApprovalUser("true".equalsIgnoreCase(value));
                }
            }
        }
    }

    private boolean hasChild(Element element, String localName) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && localName.equals(localName((Element) child))) {
                return true;
            }
        }
        return false;
    }

    private String localName(Element element) {
        String local = element.getLocalName();
        if (local != null) {
            return local;
        }
        String name = element.getNodeName();
        int index = name == null ? -1 : name.indexOf(':');
        return index >= 0 ? name.substring(index + 1) : name;
    }

    private String attr(Element element, String name) {
        NamedNodeMap attributes = element.getAttributes();
        String namespaced = null;
        String plain = null;
        String other = null;
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String nodeName = attribute.getNodeName();
            String local = nodeName;
            int index = nodeName == null ? -1 : nodeName.indexOf(':');
            if (index >= 0) {
                local = nodeName.substring(index + 1);
            }
            if (!name.equals(local)) {
                continue;
            }
            String value = attribute.getNodeValue();
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (index < 0) {
                plain = value.trim();
                continue;
            }
            String namespace = attribute.getNamespaceURI();
            if (FLOW_NAMESPACE.equals(namespace) || "flow".equals(nodeName.substring(0, index))) {
                // 设计器写入的 flow:xxx 属性优先级最高，避免与历史明文属性并存时读到旧值
                if (namespaced == null) {
                    namespaced = value.trim();
                }
            } else if (other == null) {
                other = value.trim();
            }
        }
        return firstNonEmpty(namespaced, plain, other);
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }
}

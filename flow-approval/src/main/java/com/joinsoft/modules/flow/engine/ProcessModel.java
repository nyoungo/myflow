package com.joinsoft.modules.flow.engine;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 解析后的流程模型。
 */
@Data
public class ProcessModel {

    private String id;
    private String name;
    private String category;
    private String documentation;

    private String startEventId;
    private List<String> endEventIds = new ArrayList<>();
    private Map<String, ProcessNode> nodes = new LinkedHashMap<>();
    private Map<String, SequenceFlowDef> flows = new LinkedHashMap<>();

    public ProcessNode node(String nodeId) {
        return nodes.get(nodeId);
    }

    public SequenceFlowDef flow(String flowId) {
        return flows.get(flowId);
    }

    public List<SequenceFlowDef> outgoingOf(String nodeId) {
        return flows.values().stream()
                .filter(flow -> nodeId != null && nodeId.equals(flow.getSourceRef()))
                .collect(Collectors.toList());
    }

    public List<SequenceFlowDef> incomingOf(String nodeId) {
        return flows.values().stream()
                .filter(flow -> nodeId != null && nodeId.equals(flow.getTargetRef()))
                .collect(Collectors.toList());
    }

    public List<ProcessNode> userTasks() {
        return nodes.values().stream().filter(ProcessNode::isUserTask).collect(Collectors.toList());
    }

    public void addNode(ProcessNode node) {
        nodes.put(node.getId(), node);
    }

    public void addFlow(SequenceFlowDef flow) {
        flows.put(flow.getId(), flow);
    }

    /**
     * 依据顺序流补齐节点的 incoming / outgoing。
     */
    public void rebuildRelations() {
        for (ProcessNode node : nodes.values()) {
            node.setIncoming(new ArrayList<>());
            node.setOutgoing(new ArrayList<>());
        }
        for (SequenceFlowDef flow : flows.values()) {
            ProcessNode source = nodes.get(flow.getSourceRef());
            ProcessNode target = nodes.get(flow.getTargetRef());
            if (source != null) {
                source.getOutgoing().add(flow.getId());
            }
            if (target != null) {
                target.getIncoming().add(flow.getId());
            }
        }
    }
}

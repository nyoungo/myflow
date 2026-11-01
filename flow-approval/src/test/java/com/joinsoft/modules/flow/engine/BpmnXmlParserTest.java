package com.joinsoft.modules.flow.engine;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BPMN 解析器单元测试。
 */
class BpmnXmlParserTest {

    private final BpmnXmlParser parser = new BpmnXmlParser();

    private String resource(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("资源不存在: " + name).isNotNull();
            Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parsesLeaveProcess() {
        ProcessModel model = parser.parse(resource("bpmn/leave.bpmn20.xml"));

        assertThat(model.getId()).isEqualTo("leave");
        assertThat(model.getStartEventId()).isEqualTo("start");
        assertThat(model.getEndEventIds()).containsExactly("end");
        assertThat(model.getFlows()).hasSize(3);
        assertThat(model.userTasks()).extracting(ProcessNode::getId)
                .containsExactly("deptApprove", "hrApprove");

        ProcessNode dept = model.node("deptApprove");
        assertThat(dept.getAssignee()).isEqualTo("manager");
        assertThat(dept.getOutgoing()).containsExactly("flow2");
        assertThat(dept.getIncoming()).containsExactly("flow1");
    }

    @Test
    void parsesExclusiveGatewayConditions() {
        ProcessModel model = parser.parse(resource("bpmn/expense.bpmn20.xml"));

        assertThat(model.node("amountGateway").getType()).isEqualTo(ProcessNode.EXCLUSIVE_GATEWAY);
        assertThat(model.flow("flow_high").getConditionExpression())
                .contains("amount").contains("1000");
        assertThat(model.outgoingOf("managerApprove")).hasSize(1);
        assertThat(model.incomingOf("amountGateway")).hasSize(1);
    }

    @Test
    void rejectsBlankXml() {
        assertThatThrownBy(() -> parser.parse("  "))
                .hasMessageContaining("BPMN 内容为空");
    }
}

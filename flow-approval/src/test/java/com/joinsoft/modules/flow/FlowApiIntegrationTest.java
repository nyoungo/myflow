package com.joinsoft.modules.flow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端接口测试：登录、发起、待办、审批、历史、流程图。
 * 使用 H2 内存库 + 内存 TokenStore，不依赖 MySQL/Redis。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "flow.demo.enabled=true",
        "flow.token-store=memory"
})
class FlowApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<Map<String, Object>>() {
            };

    @Autowired
    private TestRestTemplate rest;

    @Test
    void leaveFlowEndToEnd() {
        String businessId = "IT-" + System.currentTimeMillis();
        String managerToken = login("manager", "manager123");
        String adminToken = login("admin", "admin123");

        // 1. 发起请假流程
        String starterToken = login("zhangsan", "123456");
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("days", 2);
        variables.put("reason", "集成测试");
        Map<String, Object> startBody = new LinkedHashMap<>();
        startBody.put("workflowId", "leave");
        startBody.put("businessId", businessId);
        startBody.put("title", "集成测试请假申请");
        startBody.put("url", "/leave/" + businessId);
        startBody.put("variables", variables);

        Map<String, Object> startResult = post("/flow/start", starterToken, startBody);
        assertThat(startResult.get("success")).isEqualTo(true);
        Map<String, Object> startData = result(startResult);
        String businessKey = (String) startData.get("businessKey");
        String procInstId = (String) startData.get("procInstId");
        assertThat(businessKey).isEqualTo("leave." + businessId);
        assertThat(procInstId).isNotBlank();

        // 2. 部门经理待办并审批通过
        Map<String, Object> managerTask = findTodo(managerToken, businessKey);
        assertThat(managerTask).as("部门经理应有待办").isNotNull();
        Map<String, Object> approve = new LinkedHashMap<>();
        approve.put("taskId", managerTask.get("taskId"));
        approve.put("comment", "部门经理同意");
        assertThat(post("/flow/pass", managerToken, approve).get("success")).isEqualTo(true);

        // 3. 人事待办并审批通过
        Map<String, Object> hrTask = findTodo(adminToken, businessKey);
        assertThat(hrTask).as("人事应有待办").isNotNull();
        Map<String, Object> hrApprove = new LinkedHashMap<>();
        hrApprove.put("taskId", hrTask.get("taskId"));
        hrApprove.put("comment", "人事同意");
        assertThat(post("/flow/pass", adminToken, hrApprove).get("success")).isEqualTo(true);

        // 4. 实例已完成
        Map<String, Object> instance = getInstance(adminToken, businessId);
        assertThat(instance).isNotNull();
        assertThat(instance.get("state")).isEqualTo("2");

        // 5. 审批历史
        Map<String, Object> hisResponse = get("/flow/model/getHis?businessKey=" + businessKey, adminToken);
        assertThat(hisResponse.get("success")).isEqualTo(true);
        assertThat((List<?>) hisResponse.get("result")).hasSize(2);

        // 6. 流程图（Base64 SVG 放在 message 字段，与原项目一致）
        Map<String, Object> picResponse = get("/flow/model/getPic?businessKey=" + businessKey, adminToken);
        assertThat(picResponse.get("success")).isEqualTo(true);
        assertThat((String) picResponse.get("message")).startsWith("PHN2Zy");

        // 7. 流程定义分组
        Map<String, Object> groupResponse = get("/flow/model/groupList", adminToken);
        assertThat(groupResponse.get("success")).isEqualTo(true);
        assertThat(groupResponse.get("result")).isNotNull();
    }

    @Test
    void rejectsAnonymousAccess() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/flow/actBusinessData/todoList", HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo(401);
    }

    @Test
    void unknownDefinitionFailsGracefully() {
        String token = login("admin", "admin123");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", "not-exists");
        body.put("businessId", "X-" + System.currentTimeMillis());
        Map<String, Object> response = post("/flow/start", token, body);
        assertThat(response.get("success")).isEqualTo(false);
        assertThat((String) response.get("message")).contains("找不到指定流程");
    }

    // ------------------------------------------------------------------

    private String login(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        Map<String, Object> response = post("/auth/login", null, body, false);
        assertThat(response.get("success")).as("登录失败: " + username).isEqualTo(true);
        Map<String, Object> data = result(response);
        String token = (String) data.get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private Map<String, Object> findTodo(String token, String businessKey) {
        Map<String, Object> response = get("/flow/actBusinessData/todoList?pageNo=1&pageSize=100", token);
        assertThat(response.get("success")).isEqualTo(true);
        Map<String, Object> page = result(response);
        List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");
        return records.stream()
                .filter(item -> businessKey.equals(item.get("businessKey")))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> getInstance(String token, String businessId) {
        Map<String, Object> response = get("/flow/actBusinessData/list?pageNo=1&pageSize=10&businessId=" + businessId, token);
        assertThat(response.get("success")).isEqualTo(true);
        List<Map<String, Object>> records = (List<Map<String, Object>>) result(response).get("records");
        return records.isEmpty() ? null : records.get(0);
    }

    private Map<String, Object> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token, true)), MAP_TYPE).getBody();
    }

    private Map<String, Object> post(String path, String token, Map<String, Object> body) {
        return post(path, token, body, true);
    }

    private Map<String, Object> post(String path, String token, Map<String, Object> body, boolean withAuth) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(token, withAuth)), MAP_TYPE);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpHeaders headers(String token, boolean auth) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (auth && token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(Map<String, Object> response) {
        Object value = response.get("result");
        assertThat(value).as("响应 result 为空: " + response).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}

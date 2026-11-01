package com.joinsoft.modules.flow.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审批人解析：显式 assignee / candidateUsers / candidateGroups + userFilter + 动态变量覆盖。
 */
@Slf4j
@Component
public class TaskAssigner {

    private final ISysUserService userService;

    public TaskAssigner(ISysUserService userService) {
        this.userService = userService;
    }

    @Data
    public static class Assignment {
        private String assignee;
        private List<String> candidateUsers = new ArrayList<>();
        private List<String> candidateGroups = new ArrayList<>();
        private List<String> users = new ArrayList<>();

        public boolean isEmpty() {
            return users.isEmpty();
        }
    }

    public Assignment resolve(ProcessNode node, ActBusinessData instance, String previousAssignee,
                              String explicitAssignee) {
        Map<String, Object> variables = resolveVariables(instance);
        Assignment assignment = new Assignment();
        assignment.setCandidateGroups(new ArrayList<>(node.getCandidateGroups()));

        List<String> explicitUsers = normalizeUsers(splitCsv(explicitAssignee));
        List<String> users = new ArrayList<>();
        String assignee = null;

        if (!explicitUsers.isEmpty()) {
            users.addAll(explicitUsers);
            if (explicitUsers.size() == 1) {
                assignee = explicitUsers.get(0);
            }
        } else {
            List<String> assigneeAttr = normalizeUsers(splitCsv(resolvePlaceholders(node.getAssignee(), variables)));
            if (assigneeAttr.size() == 1) {
                assignee = assigneeAttr.get(0);
                users.add(assignee);
            } else {
                users.addAll(assigneeAttr);
            }
            for (String candidate : node.getCandidateUsers()) {
                users.addAll(normalizeUsers(splitCsv(resolvePlaceholders(candidate, variables))));
            }
            if (users.isEmpty() && !node.getCandidateGroups().isEmpty()) {
                users.addAll(resolveGroups(node.getCandidateGroups()));
            }
        }

        users = distinct(users);
        if (!explicitUsers.isEmpty()) {
            // 显式指定审批人时不做 userFilter 过滤，但保留候选角色信息
            assignment.setUsers(users);
            assignment.setAssignee(assignee);
            assignment.setCandidateUsers(users.size() > 1 ? users : new ArrayList<>());
            return assignment;
        }

        users = applyFilters(users, node, instance, previousAssignee);
        assignment.setUsers(users);

        if (users.size() == 1) {
            assignment.setAssignee(assignee != null ? assignee : users.get(0));
        } else if (assignee != null && users.contains(assignee) && node.getCandidateUsers().isEmpty()) {
            assignment.setAssignee(assignee);
        } else {
            assignment.setCandidateUsers(users);
        }
        if (assignment.getAssignee() != null) {
            assignment.setCandidateUsers(new ArrayList<>());
        }
        return assignment;
    }

    public List<SysUser> resolveUsers(ProcessNode node) {
        List<String> userIds = new ArrayList<>();
        userIds.addAll(splitCsv(node.getAssignee()));
        for (String candidate : node.getCandidateUsers()) {
            userIds.addAll(splitCsv(candidate));
        }
        Set<String> ids = new LinkedHashSet<>(userIds);
        List<SysUser> result = new ArrayList<>();
        if (!ids.isEmpty()) {
            result.addAll(userService.listByIds(ids));
        }
        result.addAll(userService.listByRoleCodes(node.getCandidateGroups()));
        return result.stream().collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a))
                .values().stream().collect(Collectors.toList());
    }

    private List<String> applyFilters(List<String> users, ProcessNode node, ActBusinessData instance,
                                      String previousAssignee) {
        if (users.isEmpty() || node.getUserFilters().isEmpty()) {
            return users;
        }
        List<String> filtered = new ArrayList<>(users);
        for (String filter : node.getUserFilters()) {
            switch (filter) {
                case "SameDepAsStartUser":
                    filtered = filterBySameDept(filtered, instance.getStartUserId());
                    break;
                case "SameDepAsLastUser":
                    filtered = filterBySameDept(filtered, previousAssignee);
                    break;
                case "Leader":
                    filtered = filtered.stream()
                            .filter(userId -> userService.isLeader(userId) || hasLeaderRole(userId))
                            .collect(Collectors.toList());
                    break;
                default:
                    log.warn("未知的 userFilter: {}", filter);
                    break;
            }
        }
        return filtered;
    }

    private List<String> filterBySameDept(List<String> userIds, String referenceUserId) {
        if (!StringUtils.hasText(referenceUserId)) {
            return new ArrayList<>();
        }
        SysUser reference = userService.getById(referenceUserId);
        if (reference == null || !StringUtils.hasText(reference.getDeptId())) {
            return new ArrayList<>();
        }
        Map<String, SysUser> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));
        return userIds.stream()
                .filter(userId -> {
                    SysUser user = users.get(userId);
                    return user != null && reference.getDeptId().equals(user.getDeptId());
                })
                .collect(Collectors.toList());
    }

    private boolean hasLeaderRole(String userId) {
        SysUser user = userService.getById(userId);
        if (user == null || !StringUtils.hasText(user.getRoleCodes())) {
            return false;
        }
        String roles = user.getRoleCodes().toLowerCase();
        return roles.contains("leader") || roles.contains("manager");
    }

    private List<String> resolveGroups(List<String> groups) {
        return userService.listByRoleCodes(groups).stream().map(SysUser::getId).collect(Collectors.toList());
    }

    private Map<String, Object> resolveVariables(ActBusinessData instance) {
        Map<String, Object> variables = com.joinsoft.common.util.JsonUtils.toMap(instance.getVariables());
        if (StringUtils.hasText(instance.getStartUserId())) {
            variables.putIfAbsent("startUserId", instance.getStartUserId());
            variables.putIfAbsent("initiator", instance.getStartUserId());
        }
        if (StringUtils.hasText(instance.getStartUserName())) {
            variables.putIfAbsent("startUserName", instance.getStartUserName());
        }
        if (StringUtils.hasText(instance.getStartDepart())) {
            variables.putIfAbsent("startDepart", instance.getStartDepart());
        }
        if (StringUtils.hasText(instance.getStartRole())) {
            variables.putIfAbsent("startRole", instance.getStartRole());
        }
        return variables;
    }

    private String resolvePlaceholders(String value, Map<String, Object> variables) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String result = value;
        int start = result.indexOf("${");
        while (start >= 0) {
            int end = result.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String key = result.substring(start + 2, end).trim();
            Object resolved = variables.get(key);
            String replacement = resolved == null ? "" : String.valueOf(resolved);
            result = result.substring(0, start) + replacement + result.substring(end + 1);
            start = result.indexOf("${", start + replacement.length());
        }
        return result;
    }

    /**
     * 审批人既可配置用户ID，也可配置用户名；统一转换为用户ID存储，便于待办查询。
     */
    private List<String> normalizeUsers(List<String> users) {
        List<String> normalized = new ArrayList<>(users.size());
        for (String value : users) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String candidate = value.trim();
            SysUser user = userService.getById(candidate);
            if (user == null) {
                user = userService.getByUsername(candidate);
            }
            normalized.add(user != null ? user.getId() : candidate);
        }
        return normalized;
    }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    /** 供审批人登录后自动认领的场景使用。 */
    public boolean canHandle(ActBusinessData instance, LoginUser user) {
        return user != null;
    }
}

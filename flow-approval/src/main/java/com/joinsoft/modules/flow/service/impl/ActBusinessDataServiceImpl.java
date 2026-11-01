package com.joinsoft.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.common.util.DateUtils;
import com.joinsoft.common.util.JsonUtils;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.engine.ProcessModel;
import com.joinsoft.modules.flow.engine.ProcessNode;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.FlowComment;
import com.joinsoft.modules.flow.entity.FlowTask;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.FlowCommentMapper;
import com.joinsoft.modules.flow.mapper.FlowTaskMapper;
import com.joinsoft.modules.flow.service.IActBusinessDataService;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ActBusinessDataServiceImpl extends ServiceImpl<ActBusinessDataMapper, ActBusinessData>
        implements IActBusinessDataService {

    private final FlowTaskMapper taskMapper;
    private final FlowCommentMapper commentMapper;
    private final FlowEngine engine;
    private final ISysUserService userService;

    public ActBusinessDataServiceImpl(FlowTaskMapper taskMapper, FlowCommentMapper commentMapper,
                                      FlowEngine engine, ISysUserService userService) {
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.engine = engine;
        this.userService = userService;
    }

    @Override
    public ActBusinessData getByBusinessId(String businessId) {
        if (!StringUtils.hasText(businessId)) {
            return null;
        }
        return getOne(new QueryWrapper<ActBusinessData>().eq("business_id", businessId).last("limit 1"), false);
    }

    @Override
    public ActBusinessData getByProcInstId(String procInstId) {
        if (!StringUtils.hasText(procInstId)) {
            return null;
        }
        return getOne(new QueryWrapper<ActBusinessData>().eq("proc_inst_id", procInstId).last("limit 1"), false);
    }

    @Override
    public List<ActBusinessData> getByBusinessIds(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return new ArrayList<>();
        }
        return list(new QueryWrapper<ActBusinessData>().in("business_id", idList));
    }

    @Override
    public void updateCommentFlowState(String id, String flowState) {
        if (!StringUtils.hasText(id)) {
            return;
        }
        FlowComment comment = commentMapper.selectById(id);
        if (comment != null) {
            comment.setFlowState(flowState);
            commentMapper.updateById(comment);
        }
    }

    // ------------------------------------------------------------------
    // 待办
    // ------------------------------------------------------------------

    @Override
    public Result<?> queryTodoList(Page<Map<String, Object>> page, ActBusinessData query) {
        LoginUser me = currentUser();
        if (me == null) {
            return Result.OK(page);
        }
        QueryWrapper<FlowTask> wrapper = new QueryWrapper<FlowTask>()
                .eq("status", FlowTask.STATUS_ACTIVE)
                .inSql("inst_id", "select id from flow_inst where state = '1'")
                .and(w -> {
                    w.eq("assignee", me.getId())
                            .or().like("candidate_users", "," + me.getId() + ",");
                    for (String role : me.getRoleCodeList()) {
                        w.or().like("candidate_groups", "," + role + ",");
                    }
                })
                .orderByAsc("start_time");
        if (!applyInstanceFilter(wrapper, query)) {
            return Result.OK(page);
        }

        Page<FlowTask> taskPage = taskMapper.selectPage(new Page<>(page.getCurrent(), page.getSize()), wrapper);
        List<FlowTask> tasks = taskPage.getRecords();
        page.setTotal(taskPage.getTotal());
        if (tasks.isEmpty()) {
            return Result.OK(page);
        }

        Map<String, ActBusinessData> instances = loadInstances(tasks.stream()
                .map(FlowTask::getInstId).collect(Collectors.toList()));
        Map<String, SysUser> users = loadUsers(tasks, instances);

        List<Map<String, Object>> records = new ArrayList<>();
        for (FlowTask task : tasks) {
            ActBusinessData instance = instances.get(task.getInstId());
            if (instance == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", task.getId());
            map.put("taskId", task.getId());
            map.put("createTime", DateUtils.format(task.getStartTime()));
            map.put("variables", JsonUtils.toMap(instance.getVariables()));
            map.put("processDefinitionId", instance.getDefId());
            map.put("suspensionState", task.getDelegationState());
            map.put("name", task.getName());
            map.put("assignee", task.getAssignee());
            map.put("priority", task.getPriority());
            map.put("formKey", task.getFormKey());
            map.put("businessData", instance);
            map.put("tenantId", instance.getTenantId());
            map.put("startUserId", instance.getStartUserId());
            map.put("startUserName", displayName(users, instance.getStartUserId()));
            map.put("businessKey", instance.getBusinessKey());
            map.put("processName", instance.getDefName());
            map.put("processStartTime", instance.getStartTime());
            map.put("title", instance.getTitle());
            map.put("state", instance.getState());
            map.put("url", instance.getUrl());
            map.put("extendedAttributes", extensionAttributes(instance, task.getTaskDefKey()));

            if (StringUtils.hasText(task.getAssignee())) {
                map.put("personId", task.getAssignee());
                map.put("personName", displayName(users, task.getAssignee()));
            } else {
                List<String> ids = splitCsv(task.getCandidateUsers());
                map.put("personId", ids);
                map.put("personName", ids.stream().map(id -> displayName(users, id)).collect(Collectors.toList()));
            }
            records.add(map);
        }
        page.setRecords(records);
        return Result.OK(page);
    }

    // ------------------------------------------------------------------
    // 已办 / 全部
    // ------------------------------------------------------------------

    @Override
    public Result<?> queryDoneList(Page<Map<String, Object>> page, ActBusinessData query) {
        LoginUser me = currentUser();
        if (me == null) {
            return Result.OK(page);
        }
        QueryWrapper<FlowTask> wrapper = new QueryWrapper<FlowTask>()
                .eq("assignee", me.getId())
                .eq("status", FlowTask.STATUS_COMPLETED)
                .orderByDesc("end_time");
        if (!applyInstanceFilter(wrapper, query)) {
            return Result.OK(page);
        }

        List<FlowTask> all = taskMapper.selectList(wrapper);
        // 每个实例只保留最新一条已办
        Map<String, FlowTask> latest = new LinkedHashMap<>();
        for (FlowTask task : all) {
            FlowTask exists = latest.get(task.getInstId());
            if (exists == null || compare(task.getEndTime(), exists.getEndTime()) > 0) {
                latest.put(task.getInstId(), task);
            }
        }
        List<FlowTask> ordered = new ArrayList<>(latest.values());
        ordered.sort((a, b) -> compare(b.getEndTime(), a.getEndTime()));

        int from = (int) Math.max(0, (page.getCurrent() - 1) * page.getSize());
        int to = (int) Math.min(ordered.size(), from + page.getSize());
        List<FlowTask> slice = from >= ordered.size()
                ? Collections.emptyList() : new ArrayList<>(ordered.subList(from, to));
        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), ordered.size());
        result.setRecords(buildDoneRecords(slice, me));
        return Result.OK(result);
    }

    @Override
    public Result<?> queryAllList(Page<Map<String, Object>> page, ActBusinessData query) {
        LoginUser me = currentUser();
        if (me == null) {
            return Result.OK(page);
        }
        // 与参考实现一致：当前用户被指派过的全部任务（含待办），按最新完成/开始时间倒序
        QueryWrapper<FlowTask> wrapper = new QueryWrapper<FlowTask>()
                .eq("assignee", me.getId())
                .orderByDesc("start_time");
        if (!applyInstanceFilter(wrapper, query)) {
            return Result.OK(page);
        }
        Page<FlowTask> taskPage = taskMapper.selectPage(new Page<>(page.getCurrent(), page.getSize()), wrapper);
        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), taskPage.getTotal());
        result.setRecords(buildDoneRecords(taskPage.getRecords(), me));
        return Result.OK(result);
    }

    private List<Map<String, Object>> buildDoneRecords(List<FlowTask> tasks, LoginUser me) {
        if (tasks.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, ActBusinessData> instances = loadInstances(tasks.stream()
                .map(FlowTask::getInstId).collect(Collectors.toList()));
        Map<String, SysUser> users = loadUsers(tasks, instances);
        List<Map<String, Object>> records = new ArrayList<>();
        for (FlowTask task : tasks) {
            ActBusinessData instance = instances.get(task.getInstId());
            if (instance == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", instance.getId());
            map.put("procInstId", instance.getId());
            map.put("procDefId", instance.getDefId());
            map.put("businessKey", instance.getBusinessKey());
            map.put("startUserId", instance.getStartUserId());
            map.put("startUserName", displayName(users, instance.getStartUserId()));
            map.put("tenantId", instance.getTenantId());
            map.put("endTime", DateUtils.format(task.getEndTime()));
            map.put("startTime", DateUtils.format(task.getStartTime()));
            map.put("duration", task.getDuration());
            map.put("taskName", task.getName());
            map.put("taskId", task.getId());
            map.put("taskDefKey", task.getTaskDefKey());
            map.put("assignee", task.getAssignee());
            map.put("formKey", task.getFormKey());
            map.put("title", instance.getTitle());
            map.put("state", instance.getState());
            map.put("url", instance.getUrl());
            map.put("variables", JsonUtils.toMap(instance.getVariables()));
            map.put("businessData", instance);
            map.put("comment", lastComment(instance.getId(), task.getId(), me.getId()));
            map.put("extendedAttributes", extensionAttributes(instance, task.getTaskDefKey()));
            records.add(map);
        }
        return records;
    }

    private String lastComment(String instId, String taskId, String userId) {
        List<FlowComment> comments = commentMapper.selectList(new QueryWrapper<FlowComment>()
                .eq("inst_id", instId)
                .eq("task_id", taskId)
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .last("limit 1"));
        return comments.isEmpty() ? null : comments.get(0).getMessage();
    }

    // ------------------------------------------------------------------
    // 我的申请
    // ------------------------------------------------------------------

    @Override
    public Result<?> queryMyApplyList(Page<Map<String, Object>> page, ActBusinessData query) {
        LoginUser me = currentUser();
        if (me == null) {
            return Result.OK(page);
        }
        QueryWrapper<ActBusinessData> wrapper = new QueryWrapper<ActBusinessData>()
                .eq("start_user_id", me.getId())
                .orderByDesc("start_time");
        if (query != null && StringUtils.hasText(query.getTitle())) {
            wrapper.like("title", query.getTitle());
        }
        if (query != null && StringUtils.hasText(query.getState())) {
            wrapper.eq("state", query.getState());
        }
        Page<ActBusinessData> instancePage = this.page(new Page<>(page.getCurrent(), page.getSize()), wrapper);

        List<Map<String, Object>> records = new ArrayList<>();
        for (ActBusinessData instance : instancePage.getRecords()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", instance.getId());
            map.put("procInstId", instance.getId());
            map.put("name", instance.getDefName());
            map.put("title", instance.getTitle());
            map.put("createTime", DateUtils.format(instance.getStartTime()));
            map.put("startTime", instance.getStartTime());
            map.put("endTime", instance.getEndTime());
            map.put("state", instance.getState());
            map.put("taskName", instance.getTaskName());
            map.put("variables", JsonUtils.toMap(instance.getVariables()));
            map.put("businessData", instance);
            map.put("tenantId", instance.getTenantId());
            map.put("startUserId", instance.getStartUserId());
            map.put("startUser", myRealName(me));
            map.put("businessKey", instance.getBusinessKey());
            map.put("url", instance.getUrl());
            map.put("duration", DateUtils.betweenMillis(instance.getStartTime(),
                    instance.getEndTime() == null ? new Date() : instance.getEndTime()));
            records.add(map);
        }
        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), instancePage.getTotal());
        result.setRecords(records);
        return Result.OK(result);
    }

    // ------------------------------------------------------------------
    // 流程退回
    // ------------------------------------------------------------------

    @Override
    public Result<?> queryBackList(Page<Map<String, Object>> page, ActBusinessData query) {
        QueryWrapper<ActBusinessData> wrapper = new QueryWrapper<ActBusinessData>()
                .in("state", "2", "3", "4")
                .orderByDesc("end_time");
        if (query != null && StringUtils.hasText(query.getTitle())) {
            wrapper.like("title", query.getTitle());
        }
        Page<ActBusinessData> instancePage = this.page(new Page<>(page.getCurrent(), page.getSize()), wrapper);
        List<Map<String, Object>> records = new ArrayList<>();
        for (ActBusinessData instance : instancePage.getRecords()) {
            FlowTask lastTask = engine.lastCompletedTask(instance.getId());
            String comment = null;
            if (lastTask != null) {
                List<FlowComment> comments = commentMapper.selectList(new QueryWrapper<FlowComment>()
                        .eq("inst_id", instance.getId())
                        .orderByDesc("create_time")
                        .last("limit 1"));
                if (!comments.isEmpty()) {
                    comment = comments.get(0).getMessage();
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", instance.getId());
            map.put("title", instance.getTitle());
            map.put("comment", comment);
            map.put("duration", DateUtils.betweenMillis(instance.getStartTime(), instance.getEndTime()));
            map.put("startTime", instance.getStartTime());
            map.put("endTime", instance.getEndTime());
            map.put("taskId", lastTask == null ? null : lastTask.getId());
            map.put("taskName", lastTask == null ? instance.getTaskName() : lastTask.getName());
            map.put("state", instance.getState());
            map.put("businessData", instance);
            map.put("businessKey", instance.getBusinessKey());
            records.add(map);
        }
        Page<Map<String, Object>> result = new Page<>(page.getCurrent(), page.getSize(), instancePage.getTotal());
        result.setRecords(records);
        return Result.OK(result);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 按标题 / 状态筛选实例，返回 false 表示没有匹配实例。
     */
    private boolean applyInstanceFilter(QueryWrapper<FlowTask> wrapper, ActBusinessData query) {
        if (query == null) {
            return true;
        }
        boolean hasTitle = StringUtils.hasText(query.getTitle());
        boolean hasState = StringUtils.hasText(query.getState());
        if (!hasTitle && !hasState) {
            return true;
        }
        QueryWrapper<ActBusinessData> instanceWrapper = new QueryWrapper<>();
        instanceWrapper.select("id");
        if (hasTitle) {
            instanceWrapper.like("title", query.getTitle());
        }
        if (hasState) {
            instanceWrapper.eq("state", query.getState());
        }
        List<Object> ids = baseMapper.selectObjs(instanceWrapper);
        if (ids.isEmpty()) {
            return false;
        }
        wrapper.in("inst_id", ids);
        return true;
    }

    private Map<String, ActBusinessData> loadInstances(List<String> instIds) {
        List<String> ids = instIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return listByIds(ids).stream().collect(Collectors.toMap(ActBusinessData::getId, item -> item, (a, b) -> a));
    }

    private Map<String, SysUser> loadUsers(List<FlowTask> tasks, Map<String, ActBusinessData> instances) {
        Set<String> ids = new LinkedHashSet<>();
        for (FlowTask task : tasks) {
            if (StringUtils.hasText(task.getAssignee())) {
                ids.add(task.getAssignee());
            }
            ids.addAll(splitCsv(task.getCandidateUsers()));
        }
        for (ActBusinessData instance : instances.values()) {
            if (StringUtils.hasText(instance.getStartUserId())) {
                ids.add(instance.getStartUserId());
            }
        }
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return userService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    private String displayName(Map<String, SysUser> users, String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        SysUser user = users.get(userId);
        if (user == null) {
            return userId;
        }
        return StringUtils.hasText(user.getRealname()) ? user.getRealname() : user.getUsername();
    }

    private String myRealName(LoginUser me) {
        return StringUtils.hasText(me.getRealname()) ? me.getRealname() : me.getUsername();
    }

    private Map<String, Object> extensionAttributes(ActBusinessData instance, String nodeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (instance == null || !StringUtils.hasText(nodeId)) {
            return result;
        }
        try {
            ActReModelEntity def = engine.definition(instance.getDefId());
            if (def == null) {
                return result;
            }
            ProcessModel model = engine.model(def);
            ProcessNode node = model.node(nodeId);
            if (node != null) {
                result.putAll(node.getExtensionElements());
                if (StringUtils.hasText(node.getAssignee())) {
                    result.put("assignee", node.getAssignee());
                }
                if (!node.getCandidateUsers().isEmpty()) {
                    result.put("candidateUsers", node.getCandidateUsers());
                }
                if (!node.getCandidateGroups().isEmpty()) {
                    result.put("candidateGroups", node.getCandidateGroups());
                }
            }
        } catch (Exception e) {
            log.debug("读取节点扩展属性失败: {}", e.getMessage());
        }
        return result;
    }

    private LoginUser currentUser() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        return principal instanceof LoginUser ? (LoginUser) principal : null;
    }

    private List<String> splitCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toList());
    }

    private int compare(Date a, Date b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return a.compareTo(b);
    }
}

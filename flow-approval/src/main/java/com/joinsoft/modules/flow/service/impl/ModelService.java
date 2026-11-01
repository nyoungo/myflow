package com.joinsoft.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.common.util.DateUtils;
import com.joinsoft.common.util.JsonUtils;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.engine.ProcessModel;
import com.joinsoft.modules.flow.engine.ProcessNode;
import com.joinsoft.modules.flow.engine.SvgDiagramGenerator;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActHiTaskinst;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.FlowComment;
import com.joinsoft.modules.flow.entity.FlowTask;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.ActReModelMapper;
import com.joinsoft.modules.flow.mapper.FlowCommentMapper;
import com.joinsoft.modules.flow.mapper.FlowTaskMapper;
import com.joinsoft.modules.flow.service.IModelService;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

/**
 * 流程模板 / 历史 / 流程图查询服务。实现原项目 {@code ModelService} 的返回语义，
 * 但数据来自自研引擎的 {@code flow_*} 表。
 */
@Slf4j
@Service
public class ModelService implements IModelService {

    private final ActReModelMapper defMapper;
    private final ActBusinessDataMapper instanceMapper;
    private final FlowTaskMapper taskMapper;
    private final FlowCommentMapper commentMapper;
    private final ISysUserService userService;
    private final FlowEngine engine;

    public ModelService(ActReModelMapper defMapper, ActBusinessDataMapper instanceMapper,
                        FlowTaskMapper taskMapper, FlowCommentMapper commentMapper,
                        ISysUserService userService, FlowEngine engine) {
        this.defMapper = defMapper;
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.userService = userService;
        this.engine = engine;
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    @Override
    public Result<?> groupList() {
        List<ActReModelEntity> all = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .orderByAsc("def_key").orderByDesc("version"));
        Map<String, ActReModelEntity> templates = new LinkedHashMap<>();
        Map<String, Date> deployTimes = new HashMap<>();
        Map<String, Integer> publishedVersions = new HashMap<>();
        for (ActReModelEntity row : all) {
            Integer version = row.getVersion() == null ? 0 : row.getVersion();
            if (version >= 1) {
                Date deployTime = row.getDeployTime() == null ? row.getLastUpdateTime() : row.getDeployTime();
                Date exists = deployTimes.get(row.getKey());
                if (exists == null || (deployTime != null && deployTime.after(exists))) {
                    deployTimes.put(row.getKey(), deployTime);
                }
                Integer max = publishedVersions.get(row.getKey());
                if (max == null || version > max) {
                    publishedVersions.put(row.getKey(), version);
                }
            }
            ActReModelEntity current = templates.get(row.getKey());
            if (current == null) {
                templates.put(row.getKey(), row);
            } else if (version == 0 && (current.getVersion() == null || current.getVersion() >= 1)) {
                templates.put(row.getKey(), row);
            }
        }
        List<ActReModelEntity> models = new ArrayList<>(templates.values());
        for (ActReModelEntity model : models) {
            model.setDeployTime(deployTimes.get(model.getKey()));
            Integer published = publishedVersions.get(model.getKey());
            if (published != null) {
                model.setVersion(published);
            }
            if (!StringUtils.hasText(model.getDescription())) {
                model.setDescription(metaDescription(model.getMetaInfo()));
            }
            model.setInstanceCount(instanceMapper.selectCount(new QueryWrapper<ActBusinessData>()
                    .eq("def_key", model.getKey())));
        }
        Map<String, List<ActReModelEntity>> group = models.stream()
                .filter(item -> item.getKey() != null)
                .collect(Collectors.groupingBy(item -> StringUtils.hasText(item.getCategory())
                        ? item.getCategory() : "默认", LinkedHashMap::new, Collectors.toList()));
        return Result.OK(group);
    }

    @Override
    public Result<?> getDefinitionByDefId(String procDefId) {
        ActReModelEntity def = findDefinition(procDefId);
        if (def == null) {
            return Result.error("流程定义不存在");
        }
        ProcessModel model = engine.parse(def.getBpmnXml());
        return Result.OK(model.userTasks());
    }

    @Override
    public Result<?> getUsersByDefId(String procDefId, String taskDefId) {
        ActReModelEntity def = findDefinition(procDefId);
        if (def == null) {
            return Result.error("流程定义不存在");
        }
        ProcessModel model = engine.parse(def.getBpmnXml());
        ProcessNode node = model.node(taskDefId);
        if (node == null || !node.isUserTask()) {
            return Result.OK(new ArrayList<SysUser>());
        }
        Set<String> userIds = new LinkedHashSet<>();
        addCsv(userIds, node.getAssignee());
        userIds.addAll(node.getCandidateUsers());
        Map<String, SysUser> users = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser user : userService.listByIds(userIds)) {
                users.put(user.getId(), user);
            }
        }
        if (!node.getCandidateGroups().isEmpty()) {
            for (SysUser user : userService.listByRoleCodes(node.getCandidateGroups())) {
                users.putIfAbsent(user.getId(), user);
            }
        }
        return Result.OK(new ArrayList<>(users.values()));
    }

    // ------------------------------------------------------------------
    // 流程图
    // ------------------------------------------------------------------

    @Override
    public Result<?> getPic(String businessKey) {
        ActBusinessData instance = instanceByBusinessKey(businessKey);
        if (instance == null) {
            return Result.error("流程实例不存在");
        }
        ActReModelEntity def = definitionOf(instance);
        if (def == null) {
            return Result.error("流程定义不存在");
        }
        ProcessModel model = engine.parse(def.getBpmnXml());
        List<Map<String, Object>> activities = JsonUtils.toList(instance.getActivities());
        String svg = SvgDiagramGenerator.generate(model, activities);
        // 与原项目一致：Base64 图像放在 message 字段返回
        return Result.OK(SvgDiagramGenerator.toBase64(svg));
    }

    // ------------------------------------------------------------------
    // 审批历史
    // ------------------------------------------------------------------

    @Override
    public Result<?> getHis(String businessKey) {
        ActBusinessData instance = instanceByBusinessKey(businessKey);
        if (instance == null) {
            return Result.OK(new ArrayList<ActHiTaskinst>());
        }
        List<FlowTask> tasks = taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("inst_id", instance.getId())
                .orderByAsc("start_time"));
        List<FlowComment> comments = commentMapper.selectList(new QueryWrapper<FlowComment>()
                .eq("inst_id", instance.getId())
                .orderByAsc("create_time"));
        Map<String, SysUser> users = loadUsers(tasks, instance);
        Map<String, List<FlowComment>> commentsByTask = new LinkedHashMap<>();
        List<FlowComment> instanceComments = new ArrayList<>();
        for (FlowComment comment : comments) {
            if (StringUtils.hasText(comment.getTaskId())) {
                commentsByTask.computeIfAbsent(comment.getTaskId(), key -> new ArrayList<>()).add(comment);
            } else {
                instanceComments.add(comment);
            }
        }

        List<ActHiTaskinst> result = new ArrayList<>();
        for (FlowTask task : tasks) {
            List<FlowComment> taskComments = commentsByTask.get(task.getId());
            if (taskComments == null || taskComments.isEmpty()) {
                result.add(toHistoryRow(task, null, instance, users));
            } else {
                for (FlowComment comment : taskComments) {
                    result.add(toHistoryRow(task, comment, instance, users));
                }
            }
        }
        for (FlowComment comment : instanceComments) {
            ActHiTaskinst row = new ActHiTaskinst();
            row.setProcInstId(instance.getId());
            row.setProcDefId(instance.getDefId());
            row.setName("流程");
            row.setStartTime(comment.getCreateTime());
            row.setEndTime(comment.getCreateTime());
            row.setComment(comment.getMessage());
            row.setAssignee(comment.getUserId());
            row.setAssigneeName(comment.getUserName());
            row.setTaskFlow(StringUtils.hasText(comment.getFlowState()) ? comment.getFlowState() : "1");
            row.setActivityType(comment.getCommentType());
            row.setBusinessKey(instance.getBusinessKey());
            row.setTitle(instance.getTitle());
            row.setUrl(instance.getUrl());
            row.setStartUserId(instance.getStartUserId());
            row.setVariables(JsonUtils.toMap(instance.getVariables()));
            result.add(row);
        }
        result.sort((a, b) -> {
            if (a.getStartTime() == null) {
                return b.getStartTime() == null ? 0 : 1;
            }
            if (b.getStartTime() == null) {
                return -1;
            }
            return a.getStartTime().compareTo(b.getStartTime());
        });
        return Result.OK(result);
    }

    @Override
    public Result<?> getFlowInfo(String businessKey) {
        ActBusinessData instance = instanceByBusinessKey(businessKey);
        if (instance == null) {
            return Result.error("流程实例不存在");
        }
        ActReModelEntity def = definitionOf(instance);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("xml", def == null ? null : def.getBpmnXml());
        result.put("his", getHis(businessKey).getResult());
        result.put("businessData", instance);

        List<Map<String, Object>> actList = new ArrayList<>();
        for (Map<String, Object> activity : JsonUtils.toList(instance.getActivities())) {
            Map<String, Object> act = new LinkedHashMap<>();
            act.put("activityId", activity.get("id"));
            act.put("name", activity.get("name"));
            act.put("type", activity.get("type"));
            Object flow = activity.get("flow");
            if (Boolean.TRUE.equals(flow)) {
                act.put("endTime", true);
            } else {
                Object time = activity.get("time");
                act.put("endTime", time == null ? new Date() : new Date(Long.parseLong(String.valueOf(time))));
            }
            actList.add(act);
        }
        result.put("actList", actList);
        return Result.OK(result);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ActHiTaskinst toHistoryRow(FlowTask task, FlowComment comment, ActBusinessData instance,
                                       Map<String, SysUser> users) {
        ActHiTaskinst row = new ActHiTaskinst();
        row.setId(task.getId());
        row.setProcDefId(task.getDefId());
        row.setProcInstId(instance.getId());
        row.setExecutionId(task.getTokenId());
        row.setName(task.getName());
        row.setTaskDefKey(task.getTaskDefKey());
        row.setAssignee(task.getAssignee());
        row.setOwner(task.getOwner());
        row.setPriority(task.getPriority());
        row.setFormKey(task.getFormKey());
        row.setTenantId(task.getTenantId());
        row.setStartTime(task.getStartTime());
        row.setClaimTime(task.getClaimTime() == null ? null : new java.sql.Timestamp(task.getClaimTime().getTime()));
        row.setEndTime(task.getEndTime());
        row.setDuration(task.getDuration());
        row.setDeleteReason(task.getDeleteReason());
        row.setBusinessKey(instance.getBusinessKey());
        row.setTitle(instance.getTitle());
        row.setUrl(instance.getUrl());
        row.setProcessDefinitionName(instance.getDefName());
        row.setStartUserId(instance.getStartUserId());
        row.setVariables(JsonUtils.toMap(instance.getVariables()));
        row.setActivityType(task.getStatus());
        String assignee = task.getAssignee();
        if (!StringUtils.hasText(assignee) && comment != null && StringUtils.hasText(comment.getUserId())) {
            assignee = comment.getUserId();
        }
        row.setAssigneeName(displayName(users, assignee));
        if (comment != null) {
            row.setComment(comment.getMessage());
            row.setAssigneeName(StringUtils.hasText(comment.getUserName())
                    ? comment.getUserName() : row.getAssigneeName());
            row.setTaskFlow(StringUtils.hasText(comment.getFlowState()) ? comment.getFlowState() : "1");
            row.setActivityType(comment.getCommentType());
        } else {
            row.setTaskFlow("1");
            row.setAssigneeName(displayName(users, task.getAssignee()));
        }
        return row;
    }

    private Map<String, SysUser> loadUsers(List<FlowTask> tasks, ActBusinessData instance) {
        Set<String> ids = new LinkedHashSet<>();
        for (FlowTask task : tasks) {
            if (StringUtils.hasText(task.getAssignee())) {
                ids.add(task.getAssignee());
            }
        }
        if (StringUtils.hasText(instance.getStartUserId())) {
            ids.add(instance.getStartUserId());
        }
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return userService.listByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, item -> item, (a, b) -> a));
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

    private ActBusinessData instanceByBusinessKey(String businessKey) {
        if (!StringUtils.hasText(businessKey)) {
            return null;
        }
        List<ActBusinessData> list = instanceMapper.selectList(new QueryWrapper<ActBusinessData>()
                .eq("business_key", businessKey)
                .orderByDesc("start_time")
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private ActReModelEntity definitionOf(ActBusinessData instance) {
        ActReModelEntity def = instance.getDefId() == null ? null : defMapper.selectById(instance.getDefId());
        if (def != null) {
            return def;
        }
        List<ActReModelEntity> list = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", instance.getDefKey())
                .ge("version", 1)
                .orderByDesc("version")
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private ActReModelEntity findDefinition(String idOrKey) {
        if (!StringUtils.hasText(idOrKey)) {
            return null;
        }
        ActReModelEntity def = defMapper.selectById(idOrKey);
        if (def != null) {
            return def;
        }
        List<ActReModelEntity> list = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", idOrKey)
                .orderByDesc("version")
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private String metaDescription(String metaInfo) {
        if (!StringUtils.hasText(metaInfo)) {
            return null;
        }
        try {
            Object description = JsonUtils.toMap(metaInfo).get("description");
            return description == null ? null : String.valueOf(description);
        } catch (Exception e) {
            return null;
        }
    }

    private void addCsv(Set<String> target, String csv) {
        if (!StringUtils.hasText(csv)) {
            return;
        }
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    /** 供管理端统计使用：最近一次流程实例。 */
    public ActBusinessData latestInstance(String defKey) {
        List<ActBusinessData> list = instanceMapper.selectList(new QueryWrapper<ActBusinessData>()
                .eq("def_key", defKey)
                .orderByDesc("start_time")
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 供管理端展示：流程实例活动轨迹。 */
    public List<Map<String, Object>> activities(String instId) {
        ActBusinessData instance = instId == null ? null : instanceMapper.selectById(instId);
        return instance == null ? Collections.emptyList() : JsonUtils.toList(instance.getActivities());
    }

    /** 描述文本，前端列表直接展示。 */
    public String toString(ActHiTaskinst row) {
        return Objects.toString(row.getComment(), "");
    }

    /**
     * 获取当前任务的候选审批人，供原项目前端兼容使用。
     */
    public Result<?> getUsersByTaskId(String taskId) {
        FlowTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            return Result.error("任务不存在");
        }
        List<SysUser> users = new ArrayList<>();
        if (StringUtils.hasText(task.getAssignee())) {
            SysUser user = userService.getById(task.getAssignee());
            if (user != null) {
                users.add(user);
            }
        }
        if (StringUtils.hasText(task.getCandidateUsers())) {
            for (String id : task.getCandidateUsers().split(",")) {
                if (StringUtils.hasText(id)) {
                    SysUser user = userService.getById(id.trim());
                    if (user != null && users.stream().noneMatch(item -> item.getId().equals(user.getId()))) {
                        users.add(user);
                    }
                }
            }
        }
        return Result.OK(users);
    }

    /** 校验流程定义；部署与保存时复用。 */
    public ProcessModel requireModel(String bpmnXml) {
        if (!StringUtils.hasText(bpmnXml)) {
            throw new JoinsoftException("BPMN 内容不能为空");
        }
        return engine.parse(bpmnXml);
    }

    /** 最近发布时间格式化，供模板列表展示。 */
    public String deployTimeText(ActReModelEntity def) {
        return def == null ? null : DateUtils.format(def.getDeployTime());
    }
}

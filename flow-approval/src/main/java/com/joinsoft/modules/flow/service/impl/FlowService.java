package com.joinsoft.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.common.util.DateUtils;
import com.joinsoft.common.util.JsonUtils;
import com.joinsoft.common.util.SpringContextHolder;
import com.joinsoft.modules.flow.dto.ApprovalData;
import com.joinsoft.modules.flow.dto.StartData;
import com.joinsoft.modules.flow.engine.ExpressionEvaluator;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.engine.ProcessModel;
import com.joinsoft.modules.flow.engine.ProcessNode;
import com.joinsoft.modules.flow.engine.SequenceFlowDef;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.FlowComment;
import com.joinsoft.modules.flow.entity.FlowTask;
import com.joinsoft.modules.flow.hook.ActivitiHook;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.ActReModelMapper;
import com.joinsoft.modules.flow.mapper.FlowCommentMapper;
import com.joinsoft.modules.flow.mapper.FlowTaskMapper;
import com.joinsoft.modules.flow.notify.FlowMessage;
import com.joinsoft.modules.flow.notify.FlowNotifier;
import com.joinsoft.modules.flow.service.IActBusinessDataService;
import com.joinsoft.modules.flow.service.IFlowService;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FlowService implements IFlowService {

    private final FlowEngine engine;
    private final ActReModelMapper defMapper;
    private final ActBusinessDataMapper instanceMapper;
    private final FlowTaskMapper taskMapper;
    private final FlowCommentMapper commentMapper;
    private final IActBusinessDataService businessDataService;
    private final ISysUserService userService;
    private final FlowNotifier notifier;
    private final ExpressionEvaluator evaluator;

    @Value("${flow.repeal-keep-record:true}")
    private boolean repealKeepRecord;

    public FlowService(FlowEngine engine, ActReModelMapper defMapper, ActBusinessDataMapper instanceMapper,
                       FlowTaskMapper taskMapper, FlowCommentMapper commentMapper,
                       IActBusinessDataService businessDataService, ISysUserService userService,
                       FlowNotifier notifier, ExpressionEvaluator evaluator) {
        this.engine = engine;
        this.defMapper = defMapper;
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.businessDataService = businessDataService;
        this.userService = userService;
        this.notifier = notifier;
        this.evaluator = evaluator;
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> start(StartData startData) {
        if (startData == null || !StringUtils.hasText(startData.getWorkflowId())) {
            return Result.error("操作失败，缺少流程ID");
        }
        if (!StringUtils.hasText(startData.getBusinessId())) {
            return Result.error("操作失败，缺少业务ID");
        }
        ActReModelEntity definition = latestPublished(startData.getWorkflowId());
        if (definition == null) {
            return Result.error("操作失败: 找不到指定流程!");
        }
        if ("SUSPENDED".equals(definition.getStatus())) {
            return Result.error("操作失败: 该流程已挂起!");
        }
        String businessKey = startData.getWorkflowId() + "." + startData.getBusinessId();
        if (instanceMapper.selectCount(new QueryWrapper<ActBusinessData>()
                .eq("business_key", businessKey)) > 0) {
            return Result.error("操作失败: 业务已存在，请勿重复提交!");
        }

        Map<String, Object> variables = startData.getVariables() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(startData.getVariables());
        variables.put("url", startData.getUrl());
        variables.put("title", startData.getTitle());
        variables.put("flow", 1L);

        LoginUser starter = currentUser();
        String tenantId = variables.get("tenantId") == null ? "" : String.valueOf(variables.get("tenantId"));
        if (!StringUtils.hasText(tenantId) && starter != null) {
            tenantId = definition.getTenantId();
        }

        ActBusinessData instance = engine.start(definition, businessKey, startData.getBusinessId(),
                startData.getTitle(), startData.getUrl(), variables, starter, tenantId);

        ActBusinessData saved = engine.instance(instance.getId());
        callHook(definition.getKey(), hook -> hook.onStart(startData.getBusinessId(), instance.getId()));

        log.info("流程启动成功: defKey={}, businessKey={}, instId={}", definition.getKey(), businessKey, instance.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("procInstId", instance.getId());
        data.put("businessKey", businessKey);
        data.put("state", saved == null ? instance.getState() : saved.getState());
        return Result.OK(data, "操作成功");
    }

    // ------------------------------------------------------------------
    // 审批
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> pass(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        LoginUser me = requireLogin();
        FlowTask task = engine.task(approvalData.getTaskId());
        if (task == null || !FlowTask.STATUS_ACTIVE.equals(task.getStatus())) {
            return Result.error("操作失败，任务已被处理");
        }
        ActBusinessData instance = engine.instance(task.getInstId());
        if (instance == null) {
            return Result.error("操作失败，流程不存在");
        }
        if (!"1".equals(instance.getState())) {
            return Result.error("操作失败，流程已结束");
        }
        if (!StringUtils.hasText(task.getAssignee())) {
            engine.claim(task, me);
            task = engine.task(task.getId());
        } else if (!task.getAssignee().equals(me.getId()) && !isAdmin(me)) {
            return Result.error("操作失败，当前任务已被其他人领取");
        }

        Map<String, Object> variables = approvalData.getVariables() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(approvalData.getVariables());
        Long flag = approvalData.getFlag();
        variables.put("flow", flag);

        if (engine.isPendingDelegation(task)) {
            addComment(instance, task, FlowComment.TYPE_COMMENT, approvalData.getComment("完成委托"), flag);
            engine.resolveDelegation(task);
            refreshInstanceTaskInfo(instance);
            return Result.OK("操作成功");
        }

        addComment(instance, task, FlowComment.TYPE_COMMENT, approvalData.getComment(), flag);
        engine.complete(instance, task, variables, flag, approvalData.getAssignee());

        ActBusinessData latest = engine.instance(instance.getId());
        String taskName = latest == null ? task.getName() : latest.getTaskName();
        callHook(instance.getDefKey(), hook -> hook.onComplete(instance.getBusinessId(), instance.getId(),
                taskName, flag));
        if (latest != null && "2".equals(latest.getState())) {
            callHook(instance.getDefKey(), hook -> hook.onFinish(instance.getBusinessId(), instance.getId(), flag));
        }
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> turnTo(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        if (!StringUtils.hasText(approvalData.getAssignee())) {
            return Result.error("操作失败，缺少转办人员");
        }
        FlowTask task = requireActiveTask(approvalData.getTaskId());
        ActBusinessData instance = requireInstance(task.getInstId());
        addComment(instance, task, FlowComment.TYPE_COMMENT, approvalData.getComment("流程转办"),
                approvalData.getFlag());
        engine.turnTo(task, approvalData.getAssignee());
        refreshInstanceTaskInfo(instance);
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> appoint(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        if (!StringUtils.hasText(approvalData.getAssignee())) {
            return Result.error("操作失败，缺少委派人员");
        }
        if (approvalData.getAssignee().contains(",")) {
            return Result.error("操作失败，只能委派一名人员");
        }
        FlowTask task = requireActiveTask(approvalData.getTaskId());
        ActBusinessData instance = requireInstance(task.getInstId());
        LoginUser me = requireLogin();
        addComment(instance, task, FlowComment.TYPE_COMMENT, approvalData.getComment("流程委托"),
                approvalData.getFlag());
        engine.appoint(task, approvalData.getAssignee(), me.getId());
        refreshInstanceTaskInfo(instance);
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> jump(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        if (!StringUtils.hasText(approvalData.getTargetTaskDefinitionKey())) {
            return Result.error("操作失败，缺少目标节点");
        }
        FlowTask task = requireActiveTask(approvalData.getTaskId());
        ActBusinessData instance = requireInstance(task.getInstId());
        addComment(instance, task, FlowComment.TYPE_JUMP, approvalData.getComment("流程跳转"),
                approvalData.getFlag());
        engine.jump(instance, task, approvalData.getTargetTaskDefinitionKey(),
                approvalData.getVariables(), approvalData.getAssignee());
        ActBusinessData latest = engine.instance(instance.getId());
        callHook(instance.getDefKey(), hook -> hook.onJump(instance.getBusinessId(), instance.getId(),
                latest == null ? null : latest.getTaskName(), approvalData.getFlag()));
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> getBack(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        FlowTask backTask = engine.task(approvalData.getTaskId());
        if (backTask == null) {
            return Result.error("操作失败，任务不存在");
        }
        ActBusinessData instance = requireInstance(backTask.getInstId());

        List<FlowTask> activeTasks = engine.activeTasks(instance.getId());
        if (activeTasks.isEmpty() || !"1".equals(instance.getState())) {
            // 已结束流程：复活最后节点
            return backToLastNode(approvalData);
        }

        List<FlowTask> after = taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("inst_id", instance.getId())
                .eq("status", FlowTask.STATUS_COMPLETED)
                .gt("end_time", backTask.getEndTime()));
        if (!after.isEmpty()) {
            return Result.error("操作失败，下个节点已被审核");
        }

        FlowTask current = activeTasks.get(0);
        addComment(instance, current, FlowComment.TYPE_BACK, approvalData.getComment("流程退回"),
                approvalData.getFlag());
        engine.activateNode(instance, backTask.getTaskDefKey(), approvalData.getAssignee());
        ActBusinessData latest = engine.instance(instance.getId());
        callHook(instance.getDefKey(), hook -> hook.onBack(instance.getBusinessId(), instance.getId(),
                latest == null ? null : latest.getTaskName(), approvalData.getFlag()));
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> backToLastNode(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        FlowTask task = engine.task(approvalData.getTaskId());
        if (task == null) {
            return Result.error("操作失败，任务不存在");
        }
        ActBusinessData instance = requireInstance(task.getInstId());
        engine.addComment(instance.getId(), task, FlowComment.TYPE_BACK,
                currentUserId(), currentUserName(), approvalData.getComment("流程退回"), "1");
        engine.activateNode(instance, task.getTaskDefKey(), approvalData.getAssignee());
        ActBusinessData latest = engine.instance(instance.getId());
        callHook(instance.getDefKey(), hook -> hook.onBack(instance.getBusinessId(), instance.getId(),
                latest == null ? null : latest.getTaskName(), approvalData.getFlag()));
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> repeal(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        FlowTask task = engine.task(approvalData.getTaskId());
        if (task == null) {
            return Result.error("操作失败，任务已被处理");
        }
        ActBusinessData instance = requireInstance(task.getInstId());
        LoginUser me = requireLogin();
        if (!me.getId().equals(instance.getStartUserId()) && !isAdmin(me)) {
            return Result.error("操作失败，非流程发起者不能撤销流程");
        }
        if (!"1".equals(instance.getState())) {
            return Result.error("操作失败，流程已结束");
        }
        String comment = approvalData.getComment("流程撤销");
        if (repealKeepRecord) {
            addComment(instance, task, FlowComment.TYPE_CANCEL, comment, approvalData.getFlag());
            engine.repeal(instance, comment, me.getId(), displayName(me.getId()));
        } else {
            engine.cancelActiveTasks(instance, comment);
            commentMapper.delete(new QueryWrapper<FlowComment>().eq("inst_id", instance.getId()));
            taskMapper.delete(new QueryWrapper<FlowTask>().eq("inst_id", instance.getId()));
            instanceMapper.deleteById(instance.getId());
        }
        callHook(instance.getDefKey(), hook -> hook.onRepeal(instance.getBusinessId(), instance.getId()));
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> claim(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        FlowTask task = requireActiveTask(approvalData.getTaskId());
        if (StringUtils.hasText(task.getAssignee())) {
            return Result.error("操作失败，任务已被领取");
        }
        LoginUser me = requireLogin();
        engine.claim(task, me);
        ActBusinessData instance = engine.instance(task.getInstId());
        if (instance != null) {
            refreshInstanceTaskInfo(instance);
        }
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> urge(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        FlowTask task = requireActiveTask(approvalData.getTaskId());
        ActBusinessData instance = requireInstance(task.getInstId());
        List<String> userIds = new ArrayList<>();
        if (StringUtils.hasText(task.getAssignee())) {
            userIds.add(task.getAssignee());
        } else {
            userIds.addAll(splitCsv(task.getCandidateUsers()));
            if (userIds.isEmpty() && StringUtils.hasText(task.getCandidateGroups())) {
                userIds.addAll(userService.listByRoleCodes(splitCsv(task.getCandidateGroups())).stream()
                        .map(SysUser::getId).collect(Collectors.toList()));
            }
        }
        if (!userIds.isEmpty()) {
            FlowMessage message = new FlowMessage();
            message.setType(FlowMessage.TYPE_URGE);
            message.setUserIds(userIds);
            message.setTitle("催办：" + instance.getTitle());
            message.setContent("您收到催办请求，请及时办理：" + instance.getTitle());
            message.setInstId(instance.getId());
            message.setTaskId(task.getId());
            message.setBusinessId(instance.getBusinessId());
            message.setDefKey(instance.getDefKey());
            notifier.notify(message);
        }
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> terminal(ApprovalData approvalData) {
        if (approvalData == null || !StringUtils.hasText(approvalData.getTaskId())) {
            return Result.error("操作失败，缺少任务ID");
        }
        FlowTask task = requireActiveTask(approvalData.getTaskId());
        ActBusinessData instance = requireInstance(task.getInstId());
        LoginUser me = requireLogin();
        addComment(instance, task, FlowComment.TYPE_TERMINAL, approvalData.getComment("流程终止"),
                approvalData.getFlag());
        engine.terminate(instance, approvalData.getComment("流程终止"), me.getId(), displayName(me.getId()));
        callHook(instance.getDefKey(), hook -> hook.onTerminal(instance.getBusinessId(), instance.getId()));
        return Result.OK("操作成功");
    }

    // ------------------------------------------------------------------
    // 预测
    // ------------------------------------------------------------------

    @Override
    public Result<?> forecast(String procInstId) {
        ActBusinessData instance = engine.instance(procInstId);
        if (instance == null) {
            return Result.error("操作失败，流程不存在");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<FlowTask> tasks = taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("inst_id", instance.getId())
                .orderByAsc("start_time"));
        for (FlowTask task : tasks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", task.getTaskDefKey());
            map.put("name", task.getName());
            map.put("finished", FlowTask.STATUS_COMPLETED.equals(task.getStatus()));
            result.add(map);
        }
        ActReModelEntity def = engine.definition(instance.getDefId());
        if (def == null) {
            return Result.OK(result);
        }
        ProcessModel model = engine.model(def);
        Map<String, Object> variables = JsonUtils.toMap(instance.getVariables());
        Set<String> seen = new HashSet<>();
        for (FlowTask task : engine.activeTasks(instance.getId())) {
            for (SequenceFlowDef flow : model.outgoingOf(task.getTaskDefKey())) {
                collectNext(model, flow, variables, result, seen, 0);
            }
        }
        return Result.OK(result);
    }

    private void collectNext(ProcessModel model, SequenceFlowDef flow, Map<String, Object> variables,
                             List<Map<String, Object>> result, Set<String> seen, int depth) {
        if (depth > 20 || flow == null) {
            return;
        }
        if (StringUtils.hasText(flow.getConditionExpression())
                && !evaluator.evaluate(flow.getConditionExpression(), variables)) {
            return;
        }
        ProcessNode node = model.node(flow.getTargetRef());
        if (node == null) {
            return;
        }
        if (node.isEnd()) {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("id", node.getId());
            end.put("name", StringUtils.hasText(node.getName()) ? node.getName() : "结束");
            result.add(end);
            return;
        }
        if (node.isUserTask()) {
            if (seen.add(node.getId())) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", node.getId());
                map.put("name", node.getName());
                map.put("finished", false);
                result.add(map);
            }
            return;
        }
        for (SequenceFlowDef next : model.outgoingOf(node.getId())) {
            collectNext(model, next, variables, result, seen, depth + 1);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ActReModelEntity latestPublished(String defKey) {
        List<ActReModelEntity> list = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", defKey)
                .ge("version", 1)
                .orderByDesc("version")
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private FlowTask requireActiveTask(String taskId) {
        FlowTask task = engine.task(taskId);
        if (task == null || !FlowTask.STATUS_ACTIVE.equals(task.getStatus())) {
            throw new JoinsoftException("操作失败，任务已被处理");
        }
        return task;
    }

    private ActBusinessData requireInstance(String instId) {
        ActBusinessData instance = engine.instance(instId);
        if (instance == null) {
            throw new JoinsoftException("操作失败，流程不存在");
        }
        return instance;
    }

    private LoginUser requireLogin() {
        LoginUser user = currentUser();
        if (user == null) {
            throw new JoinsoftException("操作失败，未登录");
        }
        return user;
    }

    private LoginUser currentUser() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        return principal instanceof LoginUser ? (LoginUser) principal : null;
    }

    private String currentUserId() {
        LoginUser user = currentUser();
        return user == null ? "system" : user.getId();
    }

    private String currentUserName() {
        LoginUser user = currentUser();
        if (user == null) {
            return "系统";
        }
        return StringUtils.hasText(user.getRealname()) ? user.getRealname() : user.getUsername();
    }

    private String displayName(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        SysUser user = userService.getById(userId);
        if (user == null) {
            return userId;
        }
        return StringUtils.hasText(user.getRealname()) ? user.getRealname() : user.getUsername();
    }

    private boolean isAdmin(LoginUser user) {
        return user != null && user.hasRole("admin");
    }

    private void addComment(ActBusinessData instance, FlowTask task, String type, String message, Long flag) {
        LoginUser me = currentUser();
        engine.addComment(instance.getId(), task, type,
                me == null ? "system" : me.getId(),
                me == null ? "系统" : (StringUtils.hasText(me.getRealname()) ? me.getRealname() : me.getUsername()),
                message, flag == null ? "1" : String.valueOf(flag));
    }

    private void refreshInstanceTaskInfo(ActBusinessData instance) {
        List<FlowTask> active = engine.activeTasks(instance.getId());
        instance.setTaskId(active.stream().map(FlowTask::getId).collect(Collectors.joining(",")));
        instance.setTaskName(active.stream().map(FlowTask::getName).collect(Collectors.joining(",")));
        instance.setEndTime(null);
        instance.setState("1");
        instanceMapper.updateById(instance);
    }

    private List<String> splitCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toList());
    }

    private void callHook(String defKey, HookConsumer consumer) {
        if (!StringUtils.hasText(defKey)) {
            return;
        }
        try {
            if (!SpringContextHolder.containsBean(defKey)) {
                return;
            }
            ActivitiHook hook = SpringContextHolder.getBean(defKey, ActivitiHook.class);
            consumer.accept(hook);
        } catch (Exception e) {
            log.warn("流程 Hook 执行失败: defKey={}, error={}", defKey, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface HookConsumer {
        void accept(ActivitiHook hook);
    }
}

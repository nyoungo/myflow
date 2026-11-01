package com.joinsoft.modules.flow.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.common.util.DateUtils;
import com.joinsoft.common.util.JsonUtils;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.FlowComment;
import com.joinsoft.modules.flow.entity.FlowTask;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.ActReModelMapper;
import com.joinsoft.modules.flow.mapper.FlowCommentMapper;
import com.joinsoft.modules.flow.mapper.FlowTaskMapper;
import com.joinsoft.modules.flow.notify.FlowMessage;
import com.joinsoft.modules.flow.notify.FlowNotifier;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 轻量令牌流程引擎：支持顺序、排他网关、并行网关、并行汇聚、驳回、跳转、终止。
 */
@Slf4j
@Component
public class FlowEngine {

    private static final int MAX_STEPS = 200;
    private static final String STATE_RUNNING = "1";
    private static final String STATE_FINISHED = "2";
    private static final String STATE_TERMINATED = "3";
    private static final String STATE_REPEALED = "4";

    private final BpmnXmlParser parser;
    private final ExpressionEvaluator evaluator;
    private final TaskAssigner taskAssigner;
    private final ActReModelMapper defMapper;
    private final ActBusinessDataMapper instanceMapper;
    private final FlowTaskMapper taskMapper;
    private final FlowCommentMapper commentMapper;
    private final ISysUserService userService;
    private final FlowNotifier notifier;

    private final Map<String, ProcessModel> modelCache = new ConcurrentHashMap<>();

    public FlowEngine(BpmnXmlParser parser, ExpressionEvaluator evaluator, TaskAssigner taskAssigner,
                      ActReModelMapper defMapper, ActBusinessDataMapper instanceMapper,
                      FlowTaskMapper taskMapper, FlowCommentMapper commentMapper,
                      ISysUserService userService, FlowNotifier notifier) {
        this.parser = parser;
        this.evaluator = evaluator;
        this.taskAssigner = taskAssigner;
        this.defMapper = defMapper;
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.userService = userService;
        this.notifier = notifier;
    }

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    public ProcessModel parse(String bpmnXml) {
        return parser.parse(bpmnXml);
    }

    public ProcessModel model(ActReModelEntity def) {
        if (def == null || !StringUtils.hasText(def.getBpmnXml())) {
            throw new JoinsoftException("流程定义不存在或未配置 BPMN");
        }
        return modelCache.computeIfAbsent(def.getId(), key -> parser.parse(def.getBpmnXml()));
    }

    public void evict(String defId) {
        if (defId != null) {
            modelCache.remove(defId);
        }
    }

    public void evictAll() {
        modelCache.clear();
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public ActBusinessData start(ActReModelEntity def, String businessKey, String businessId, String title,
                                 String url, Map<String, Object> variables, LoginUser starter, String tenantId) {
        ProcessModel model = model(def);

        ActBusinessData instance = new ActBusinessData();
        String id = IdWorker.getIdStr();
        instance.setId(id);
        instance.setProcInstId(id);
        instance.setDefId(def.getId());
        instance.setDefKey(def.getKey());
        instance.setDefName(def.getName());
        instance.setBusinessKey(businessKey);
        instance.setBusinessId(businessId);
        instance.setTitle(title);
        instance.setUrl(url);
        instance.setState(STATE_RUNNING);
        instance.setTenantId(tenantId);
        instance.setStartTime(new Date());

        RunContext context = new RunContext();
        context.variables = variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
        instance.setVariables(JsonUtils.toJson(context.variables));

        if (starter != null) {
            instance.setStartUserId(starter.getId());
            instance.setStartUserName(starter.getRealname());
            instance.setStartDepart(starter.getDeptId());
            instance.setStartRole(starter.getRoleCodes());
            context.variables.putIfAbsent("startUserId", starter.getId());
            context.variables.putIfAbsent("startUserName", starter.getRealname());
            context.variables.putIfAbsent("startDepart", starter.getDeptId());
            context.variables.putIfAbsent("startRole", starter.getRoleCodes());
        }
        instance.setActiveTokens("[]");
        instance.setActivities("[]");
        instanceMapper.insert(instance);

        FlowToken token = new FlowToken(newId(), model.getStartEventId());
        context.tokens.add(token);
        context.queue.add(token);
        processQueue(instance, model, context);
        persist(instance, context);
        return instance;
    }

    // ------------------------------------------------------------------
    // 任务操作
    // ------------------------------------------------------------------

    public FlowTask task(String taskId) {
        return taskId == null ? null : taskMapper.selectById(taskId);
    }

    public List<FlowTask> activeTasks(String instId) {
        return taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("inst_id", instId)
                .eq("status", FlowTask.STATUS_ACTIVE)
                .orderByAsc("start_time"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(ActBusinessData instance, FlowTask task, Map<String, Object> variables, Long flag,
                         String assigneeOverride) {
        ProcessModel model = modelOf(instance);
        RunContext context = contextOf(instance);
        context.variables.put("flow", flag == null ? 1L : flag);
        if (variables != null) {
            context.variables.putAll(variables);
        }
        context.assigneeOverride = StringUtils.hasText(assigneeOverride) ? assigneeOverride : null;

        finishTaskRow(task, null, null, null, null);
        FlowToken token = takeToken(context, task);
        followOutgoing(instance, model, context, model.node(task.getTaskDefKey()));
        removeToken(context, token);
        processQueue(instance, model, context);
        persist(instance, context);
    }

    /**
     * 跳转到指定节点：完成当前任务后把令牌直接放到目标节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowTask jump(ActBusinessData instance, FlowTask sourceTask, String targetNodeId,
                         Map<String, Object> variables, String assigneeOverride) {
        ProcessModel model = modelOf(instance);
        if (model.node(targetNodeId) == null) {
            throw new JoinsoftException("目标节点不存在: " + targetNodeId);
        }
        RunContext context = contextOf(instance);
        if (variables != null) {
            context.variables.putAll(variables);
        }
        context.assigneeOverride = StringUtils.hasText(assigneeOverride) ? assigneeOverride : null;

        finishTaskRow(sourceTask, null, null, null, null);
        FlowToken token = takeToken(context, sourceTask);
        removeToken(context, token);

        FlowToken target = new FlowToken(newId(), targetNodeId);
        context.tokens.add(target);
        context.queue.add(target);
        processQueue(instance, model, context);

        instance.setState(STATE_RUNNING);
        instance.setEndTime(null);
        persist(instance, context);
        return activeTasks(instance.getId()).stream().findFirst().orElse(null);
    }

    /**
     * 取消所有活动任务，并把令牌重新放到指定节点（用于退回 / 复活）。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowTask activateNode(ActBusinessData instance, String nodeId, String assigneeOverride) {
        ProcessModel model = modelOf(instance);
        if (model.node(nodeId) == null) {
            throw new JoinsoftException("节点不存在: " + nodeId);
        }
        cancelActiveTasks(instance, "流程退回");
        RunContext context = contextOf(instance);
        context.tokens.clear();
        context.queue.clear();
        context.assigneeOverride = StringUtils.hasText(assigneeOverride) ? assigneeOverride : null;
        FlowToken token = new FlowToken(newId(), nodeId);
        context.tokens.add(token);
        context.queue.add(token);
        processQueue(instance, model, context);
        instance.setState(STATE_RUNNING);
        instance.setEndTime(null);
        persist(instance, context);
        return activeTasks(instance.getId()).stream().findFirst().orElse(null);
    }

    /**
     * 已结束流程回退到最后节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowTask backToLastNode(ActBusinessData instance) {
        FlowTask last = lastCompletedTask(instance.getId());
        if (last == null) {
            throw new JoinsoftException("流程没有可退回的节点");
        }
        return activateNode(instance, last.getTaskDefKey(), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void terminate(ActBusinessData instance, String reason, String userId, String userName) {
        RunContext context = contextOf(instance);
        cancelActiveTasks(instance, StringUtils.hasText(reason) ? reason : "流程终止");
        context.tokens.clear();
        context.queue.clear();
        context.activities.add(activity("end", "结束", ProcessNode.END, false));
        addComment(instance.getId(), null, FlowComment.TYPE_TERMINAL, userId, userName,
                StringUtils.hasText(reason) ? reason : "流程终止", "0");
        instance.setState(STATE_TERMINATED);
        instance.setEndTime(new Date());
        persist(instance, context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void repeal(ActBusinessData instance, String reason, String userId, String userName) {
        RunContext context = contextOf(instance);
        cancelActiveTasks(instance, StringUtils.hasText(reason) ? reason : "流程撤销");
        context.tokens.clear();
        context.queue.clear();
        addComment(instance.getId(), null, FlowComment.TYPE_CANCEL, userId, userName,
                StringUtils.hasText(reason) ? reason : "流程撤销", "0");
        instance.setState(STATE_REPEALED);
        instance.setEndTime(new Date());
        persist(instance, context);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelActiveTasks(ActBusinessData instance, String reason) {
        for (FlowTask task : activeTasks(instance.getId())) {
            finishTaskRow(task, FlowTask.STATUS_CANCELED, reason, null, null);
        }
    }

    public void claim(FlowTask task, LoginUser user) {
        if (StringUtils.hasText(task.getAssignee())) {
            throw new JoinsoftException("任务已被领取");
        }
        task.setAssignee(user.getId());
        task.setClaimTime(new Date());
        taskMapper.updateById(task);
    }

    public void turnTo(FlowTask task, String assignee) {
        List<String> users = split(assignee);
        if (users.isEmpty()) {
            throw new JoinsoftException("缺少转办人员");
        }
        if (users.size() == 1) {
            task.setAssignee(users.get(0));
            task.setCandidateUsers(null);
            task.setCandidateGroups(null);
        } else {
            task.setAssignee(null);
            task.setCandidateUsers(csv(users));
        }
        task.setClaimTime(new Date());
        taskMapper.updateById(task);
    }

    public void appoint(FlowTask task, String assignee, String operatorId) {
        List<String> users = split(assignee);
        if (users.size() != 1) {
            throw new JoinsoftException("只能委派一名人员");
        }
        task.setOwner(StringUtils.hasText(task.getAssignee()) ? task.getAssignee() : operatorId);
        task.setAssignee(users.get(0));
        task.setDelegationState(FlowTask.DELEGATION_PENDING);
        taskMapper.updateById(task);
    }

    public void resolveDelegation(FlowTask task) {
        task.setAssignee(task.getOwner());
        task.setOwner(null);
        task.setDelegationState(FlowTask.DELEGATION_RESOLVED);
        taskMapper.updateById(task);
    }

    public boolean isPendingDelegation(FlowTask task) {
        return FlowTask.DELEGATION_PENDING.equals(task.getDelegationState());
    }

    public FlowTask lastCompletedTask(String instId) {
        return taskMapper.selectOne(new QueryWrapper<FlowTask>()
                .eq("inst_id", instId)
                .eq("status", FlowTask.STATUS_COMPLETED)
                .orderByDesc("end_time")
                .last("limit 1"));
    }

    public String lastAssignee(String instId) {
        FlowTask last = lastCompletedTask(instId);
        return last == null ? null : last.getAssignee();
    }

    public void addComment(String instId, FlowTask task, String type, String userId, String userName,
                           String message, String flowState) {
        FlowComment comment = new FlowComment();
        comment.setId(IdWorker.getIdStr());
        comment.setInstId(instId);
        if (task != null) {
            comment.setTaskId(task.getId());
            comment.setTaskDefKey(task.getTaskDefKey());
            comment.setTaskName(task.getName());
        }
        comment.setCommentType(StringUtils.hasText(type) ? type : FlowComment.TYPE_COMMENT);
        comment.setUserId(userId);
        comment.setUserName(userName);
        comment.setMessage(message);
        comment.setFlowState(flowState);
        comment.setCreateTime(new Date());
        commentMapper.insert(comment);
    }

    public ActBusinessData instance(String instId) {
        return instId == null ? null : instanceMapper.selectById(instId);
    }

    public ActReModelEntity definition(String defId) {
        return defId == null ? null : defMapper.selectById(defId);
    }

    // ------------------------------------------------------------------
    // 内部：令牌推进
    // ------------------------------------------------------------------

    private void processQueue(ActBusinessData instance, ProcessModel model, RunContext context) {
        while (!context.queue.isEmpty()) {
            if (context.steps++ > MAX_STEPS) {
                throw new JoinsoftException("流程执行步数超过上限，请检查是否存在环路或自动通过死循环");
            }
            FlowToken token = context.queue.poll();
            if (token == null) {
                continue;
            }
            ProcessNode node = model.node(token.getNode());
            if (node == null) {
                log.warn("流程实例 {} 引用了不存在的节点 {}", instance.getId(), token.getNode());
                context.tokens.remove(token);
                continue;
            }
            switch (node.getType()) {
                case ProcessNode.USER_TASK:
                    createTask(instance, model, context, token, node);
                    break;
                case ProcessNode.TERMINATE_END:
                    recordNode(context, node);
                    context.tokens.remove(token);
                    terminateByNode(instance, context, node);
                    return;
                case ProcessNode.END:
                    recordNode(context, node);
                    context.tokens.remove(token);
                    break;
                case ProcessNode.PARALLEL_GATEWAY:
                    handleParallelGateway(instance, model, context, token, node);
                    break;
                case ProcessNode.EXCLUSIVE_GATEWAY:
                    handleExclusiveGateway(instance, model, context, token, node);
                    break;
                case ProcessNode.INCLUSIVE_GATEWAY:
                    handleInclusiveGateway(instance, model, context, token, node);
                    break;
                default:
                    recordNode(context, node);
                    context.tokens.remove(token);
                    followOutgoing(instance, model, context, node);
                    break;
            }
        }
    }

    private void createTask(ActBusinessData instance, ProcessModel model, RunContext context,
                            FlowToken token, ProcessNode node) {
        recordNode(context, node);
        String previousAssignee = lastAssignee(instance.getId());
        TaskAssigner.Assignment assignment = taskAssigner.resolve(node, instance, previousAssignee,
                context.assigneeOverride);

        FlowTask task = new FlowTask();
        task.setId(IdWorker.getIdStr());
        task.setInstId(instance.getId());
        task.setDefId(instance.getDefId());
        task.setTaskDefKey(node.getId());
        task.setName(StringUtils.hasText(node.getName()) ? node.getName() : node.getId());
        task.setAssignee(assignment.getAssignee());
        if (!assignment.getCandidateUsers().isEmpty()) {
            task.setCandidateUsers(csv(assignment.getCandidateUsers()));
        }
        if (!assignment.getCandidateGroups().isEmpty()) {
            task.setCandidateGroups(csv(assignment.getCandidateGroups()));
        }
        task.setFormKey(node.getFormKey());
        task.setPriority(50);
        task.setStatus(FlowTask.STATUS_ACTIVE);
        task.setTokenId(token.getId());
        task.setStartTime(new Date());
        task.setTenantId(instance.getTenantId());
        taskMapper.insert(task);

        notifyNewTask(instance, node, task, assignment);

        if (node.isAutoPass() && StringUtils.hasText(previousAssignee)
                && assignment.getUsers().contains(previousAssignee)) {
            finishTaskRow(task, FlowTask.STATUS_COMPLETED, null, "system", "系统");
            addComment(instance.getId(), task, FlowComment.TYPE_COMMENT, "system", "系统",
                    "与上一节点审批人相同，自动通过", "1");
            removeToken(context, token);
            followOutgoing(instance, model, context, node);
            return;
        }
        if (assignment.isEmpty()) {
            finishTaskRow(task, FlowTask.STATUS_COMPLETED, null, "system", "系统");
            addComment(instance.getId(), task, FlowComment.TYPE_COMMENT, "system", "系统",
                    "无符合条件审批人，自动通过", "1");
            removeToken(context, token);
            followOutgoing(instance, model, context, node);
        }
    }

    private void notifyNewTask(ActBusinessData instance, ProcessNode node, FlowTask task,
                               TaskAssigner.Assignment assignment) {
        List<String> assignees = new ArrayList<>(assignment.getUsers());
        if (assignees.isEmpty() && StringUtils.hasText(task.getAssignee())) {
            assignees.add(task.getAssignee());
        }
        if (node.isNoticeApprovalUser() && !assignees.isEmpty()) {
            notifier.notify(message(FlowMessage.TYPE_TODO, assignees,
                    "您有新的待办任务：" + instance.getTitle(),
                    String.format("您有新的待办任务【%s】，节点【%s】，请及时办理。",
                            instance.getTitle(), task.getName()), instance, task));
        }
        if (node.isNoticeStartUser() && StringUtils.hasText(instance.getStartUserId())) {
            notifier.notify(message(FlowMessage.TYPE_CC,
                    java.util.Collections.singletonList(instance.getStartUserId()),
                    "流程进度通知：" + instance.getTitle(),
                    String.format("您发起的流程【%s】已到达【%s】节点。", instance.getTitle(), task.getName()),
                    instance, task));
        }
    }

    private void handleParallelGateway(ActBusinessData instance, ProcessModel model, RunContext context,
                                       FlowToken token, ProcessNode node) {
        recordNode(context, node);
        int incoming = Math.max(1, node.getIncoming().size());
        if (incoming > 1) {
            long waiting = context.tokens.stream()
                    .filter(item -> node.getId().equals(item.getNode()))
                    .count();
            if (waiting < incoming) {
                log.debug("并行网关 {} 等待汇聚，当前 {}/{}", node.getId(), waiting, incoming);
                return;
            }
            context.tokens.removeIf(item -> node.getId().equals(item.getNode()));
        } else {
            context.tokens.remove(token);
        }
        followOutgoing(instance, model, context, node);
    }

    private void handleExclusiveGateway(ActBusinessData instance, ProcessModel model, RunContext context,
                                        FlowToken token, ProcessNode node) {
        recordNode(context, node);
        context.tokens.remove(token);
        List<SequenceFlowDef> flows = model.outgoingOf(node.getId());
        SequenceFlowDef chosen = null;
        for (SequenceFlowDef flow : flows) {
            if (flow.getId().equals(node.getDefaultFlowId())) {
                continue;
            }
            if (StringUtils.hasText(flow.getConditionExpression())
                    && evaluator.evaluate(flow.getConditionExpression(), context.variables)) {
                chosen = flow;
                break;
            }
        }
        if (chosen == null) {
            for (SequenceFlowDef flow : flows) {
                if (flow.getId().equals(node.getDefaultFlowId())
                        || !StringUtils.hasText(flow.getConditionExpression())) {
                    chosen = flow;
                    break;
                }
            }
        }
        if (chosen == null) {
            log.warn("排他网关 {} 没有满足条件的出口", node.getId());
            return;
        }
        followFlow(context, chosen);
    }

    private void handleInclusiveGateway(ActBusinessData instance, ProcessModel model, RunContext context,
                                        FlowToken token, ProcessNode node) {
        recordNode(context, node);
        context.tokens.remove(token);
        List<SequenceFlowDef> selected = new ArrayList<>();
        List<SequenceFlowDef> flows = model.outgoingOf(node.getId());
        for (SequenceFlowDef flow : flows) {
            if (flow.getId().equals(node.getDefaultFlowId())) {
                continue;
            }
            if (!StringUtils.hasText(flow.getConditionExpression())
                    || evaluator.evaluate(flow.getConditionExpression(), context.variables)) {
                selected.add(flow);
            }
        }
        if (selected.isEmpty()) {
            flows.stream().filter(flow -> flow.getId().equals(node.getDefaultFlowId()))
                    .findFirst().ifPresent(selected::add);
        }
        if (selected.isEmpty()) {
            log.warn("包容网关 {} 没有满足条件的出口", node.getId());
            return;
        }
        for (SequenceFlowDef flow : selected) {
            followFlow(context, flow);
        }
    }

    private void followOutgoing(ActBusinessData instance, ProcessModel model, RunContext context, ProcessNode node) {
        List<SequenceFlowDef> flows = model.outgoingOf(node.getId());
        if (flows.isEmpty()) {
            log.warn("节点 {} 没有出口", node.getId());
            return;
        }
        for (SequenceFlowDef flow : flows) {
            followFlow(context, flow);
        }
    }

    private void followFlow(RunContext context, SequenceFlowDef flow) {
        context.activities.add(activity(flow.getId(), flow.getName(), "sequenceFlow", true));
        FlowToken next = new FlowToken(newId(), flow.getTargetRef());
        context.tokens.add(next);
        context.queue.add(next);
    }

    private void terminateByNode(ActBusinessData instance, RunContext context, ProcessNode node) {
        for (FlowTask task : activeTasks(instance.getId())) {
            finishTaskRow(task, FlowTask.STATUS_CANCELED, "到达终止节点", null, null);
        }
        context.tokens.clear();
        context.queue.clear();
        instance.setState(STATE_TERMINATED);
        instance.setEndTime(new Date());
        addComment(instance.getId(), null, FlowComment.TYPE_TERMINAL, "system", "系统",
                "流程到达终止节点【" + (node.getName() == null ? node.getId() : node.getName()) + "】", "0");
    }

    // ------------------------------------------------------------------
    // 内部：持久化与辅助
    // ------------------------------------------------------------------

    private void persist(ActBusinessData instance, RunContext context) {
        instance.setVariables(JsonUtils.toJson(context.variables));
        instance.setActiveTokens(JsonUtils.toJson(context.tokens));
        instance.setActivities(JsonUtils.toJson(context.activities));

        if (!STATE_TERMINATED.equals(instance.getState()) && !STATE_REPEALED.equals(instance.getState())) {
            if (context.tokens.isEmpty()) {
                instance.setState(STATE_FINISHED);
                if (instance.getEndTime() == null) {
                    instance.setEndTime(new Date());
                }
            } else {
                instance.setState(STATE_RUNNING);
                instance.setEndTime(null);
            }
        }

        List<FlowTask> active = activeTasks(instance.getId());
        instance.setTaskId(active.isEmpty() ? "" : active.stream().map(FlowTask::getId)
                .collect(Collectors.joining(",")));
        instance.setTaskName(active.isEmpty() ? "" : active.stream()
                .map(task -> StringUtils.hasText(task.getName()) ? task.getName() : task.getTaskDefKey())
                .collect(Collectors.joining(",")));
        instanceMapper.updateById(instance);
    }

    private void finishTaskRow(FlowTask task, String status, String deleteReason, String userId, String userName) {
        if (task == null) {
            return;
        }
        FlowTask current = taskMapper.selectById(task.getId());
        if (current == null) {
            throw new JoinsoftException("任务不存在: " + task.getId());
        }
        if (!FlowTask.STATUS_ACTIVE.equals(current.getStatus()) && status != null) {
            throw new JoinsoftException("任务已被处理，请刷新后重试");
        }
        Date now = new Date();
        if (status != null) {
            current.setStatus(status);
        } else {
            current.setStatus(FlowTask.STATUS_COMPLETED);
        }
        if (FlowTask.STATUS_ACTIVE.equals(current.getStatus())) {
            current.setStatus(FlowTask.STATUS_COMPLETED);
        }
        current.setEndTime(now);
        current.setDuration(DateUtils.betweenMillis(current.getStartTime(), now));
        if (deleteReason != null) {
            current.setDeleteReason(deleteReason);
        }
        taskMapper.updateById(current);
    }

    private FlowToken takeToken(RunContext context, FlowTask task) {
        FlowToken token = context.tokens.stream()
                .filter(item -> task.getTokenId() != null && task.getTokenId().equals(item.getId()))
                .findFirst()
                .orElseGet(() -> context.tokens.stream()
                        .filter(item -> task.getTaskDefKey().equals(item.getNode()))
                        .findFirst().orElse(null));
        if (token == null) {
            token = new FlowToken(newId(), task.getTaskDefKey());
            context.tokens.add(token);
        }
        return token;
    }

    private void removeToken(RunContext context, FlowToken token) {
        if (token != null) {
            context.tokens.removeIf(item -> item.getId().equals(token.getId()));
        }
    }

    private RunContext contextOf(ActBusinessData instance) {
        RunContext context = new RunContext();
        context.tokens = new ArrayList<>(JsonUtils.toList(instance.getActiveTokens(), FlowToken.class));
        context.activities = new ArrayList<>(JsonUtils.toList(instance.getActivities()));
        context.variables = JsonUtils.toMap(instance.getVariables());
        return context;
    }

    private ProcessModel modelOf(ActBusinessData instance) {
        ActReModelEntity def = definition(instance.getDefId());
        if (def != null) {
            return model(def);
        }
        ActReModelEntity byKey = defMapper.selectOne(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", instance.getDefKey())
                .ge("version", 1)
                .orderByDesc("version")
                .last("limit 1"));
        if (byKey == null) {
            throw new JoinsoftException("流程定义已删除，无法继续执行: " + instance.getDefKey());
        }
        return model(byKey);
    }

    private void recordNode(RunContext context, ProcessNode node) {
        context.activities.add(activity(node.getId(), node.getName(), node.getType(), false));
    }

    private Map<String, Object> activity(String id, String name, String type, boolean flow) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", StringUtils.hasText(name) ? name : id);
        map.put("type", type);
        map.put("flow", flow);
        map.put("time", System.currentTimeMillis());
        return map;
    }

    private FlowMessage message(String type, List<String> userIds, String title, String content,
                                ActBusinessData instance, FlowTask task) {
        FlowMessage message = new FlowMessage();
        message.setType(type);
        message.setUserIds(userIds);
        message.setTitle(title);
        message.setContent(content);
        message.setInstId(instance.getId());
        message.setBusinessId(instance.getBusinessId());
        message.setDefKey(instance.getDefKey());
        if (task != null) {
            message.setTaskId(task.getId());
        }
        return message;
    }

    private String newId() {
        return IdWorker.getIdStr();
    }

    private List<String> split(String csv) {
        if (!StringUtils.hasText(csv)) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    private String csv(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>(values);
        return distinct.stream().collect(Collectors.joining(",", ",", ","));
    }

    /** 供 FlowService 复用：解析用户展示名。 */
    public String displayName(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        SysUser user = userService.getById(userId);
        return user == null ? userId : (StringUtils.hasText(user.getRealname()) ? user.getRealname() : user.getUsername());
    }

    private static final class RunContext {
        private List<FlowToken> tokens = new ArrayList<>();
        private final Deque<FlowToken> queue = new ArrayDeque<>();
        private List<Map<String, Object>> activities = new ArrayList<>();
        private Map<String, Object> variables = new LinkedHashMap<>();
        private String assigneeOverride;
        private int steps;
    }
}

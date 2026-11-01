package com.joinsoft.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.common.util.SpringContextHolder;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActRuExecution;
import com.joinsoft.modules.flow.entity.FlowComment;
import com.joinsoft.modules.flow.entity.FlowTask;
import com.joinsoft.modules.flow.hook.ActivitiHook;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.FlowTaskMapper;
import com.joinsoft.modules.flow.service.IActRuExecutionService;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ActRuExecutionServiceImpl implements IActRuExecutionService {

    private final ActBusinessDataMapper instanceMapper;
    private final FlowTaskMapper taskMapper;
    private final ISysUserService userService;
    private final FlowEngine engine;

    public ActRuExecutionServiceImpl(ActBusinessDataMapper instanceMapper, FlowTaskMapper taskMapper,
                                     ISysUserService userService, FlowEngine engine) {
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.userService = userService;
        this.engine = engine;
    }

    @Override
    public Page<ActRuExecution> getRunningPageList(String searchBusinesskey, Integer pageNo, Integer pageSize) {
        int current = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;
        LoginUser me = currentUser();
        Page<ActBusinessData> page = new Page<>(current, size);
        if (me == null) {
            return new Page<>(current, size, 0);
        }
        QueryWrapper<ActBusinessData> wrapper = new QueryWrapper<ActBusinessData>()
                .eq("start_user_id", me.getId())
                .eq("state", "1")
                .orderByDesc("start_time");
        if (StringUtils.hasText(searchBusinesskey)) {
            wrapper.eq("business_key", searchBusinesskey);
        }
        page = instanceMapper.selectPage(page, wrapper);
        List<ActRuExecution> records = new ArrayList<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (ActBusinessData instance : page.getRecords()) {
            ActRuExecution execution = new ActRuExecution();
            execution.setId(instance.getId());
            execution.setProcInstId(instance.getId());
            execution.setBusinessKey(instance.getBusinessKey());
            execution.setBusinessId(instance.getBusinessId());
            execution.setProcDefId(instance.getDefId());
            execution.setDefKey(instance.getDefKey());
            execution.setName(instance.getDefName());
            execution.setTitle(instance.getTitle());
            execution.setState(instance.getState());
            execution.setSuspensionState(1);
            execution.setStartTime(instance.getStartTime());
            execution.setEndTime(instance.getEndTime());
            execution.setStartUserId(instance.getStartUserId());
            execution.setTenantId(instance.getTenantId());
            execution.setUrl(instance.getUrl());
            List<FlowTask> tasks = activeTasks(instance.getId());
            execution.setTaskName(tasks.stream().map(FlowTask::getName).collect(Collectors.joining(" | ")));
            execution.setTaskId(tasks.stream().map(FlowTask::getId).collect(Collectors.joining(",")));
            if (StringUtils.hasText(instance.getStartUserId())) {
                userIds.add(instance.getStartUserId());
            }
            records.add(execution);
        }
        if (!userIds.isEmpty()) {
            java.util.Map<String, SysUser> users = userService.listByIds(userIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, item -> item, (a, b) -> a));
            for (ActRuExecution record : records) {
                SysUser user = users.get(record.getStartUserId());
                if (user != null) {
                    record.setStartUserName(StringUtils.hasText(user.getRealname())
                            ? user.getRealname() : user.getUsername());
                }
            }
        }
        Page<ActRuExecution> result = new Page<>(current, size, page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> repeal(ActRuExecution execution) {
        if (execution == null || !StringUtils.hasText(execution.getId())) {
            return Result.error("操作失败，缺少流程实例ID");
        }
        ActBusinessData instance = instanceMapper.selectById(execution.getId());
        if (instance == null) {
            return Result.error("操作失败，流程实例不存在");
        }
        if (!"1".equals(instance.getState())) {
            return Result.error("操作失败，流程已结束");
        }
        LoginUser me = currentUser();
        if (me == null || (!me.getId().equals(instance.getStartUserId()) && !me.hasRole("admin"))) {
            return Result.error("操作失败，非流程发起者不能撤销流程");
        }
        engine.repeal(instance, "流程管理-撤销流程", me.getId(), displayName(me));
        callHook(instance.getDefKey(), hook -> hook.onRepeal(instance.getBusinessId(), instance.getId()));
        return Result.OK("操作成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> terminal(ActRuExecution execution) {
        if (execution == null || !StringUtils.hasText(execution.getId())) {
            return Result.error("操作失败，缺少流程实例ID");
        }
        ActBusinessData instance = instanceMapper.selectById(execution.getId());
        if (instance == null) {
            return Result.error("操作失败，流程实例不存在");
        }
        if (!"1".equals(instance.getState())) {
            return Result.error("操作失败，流程已结束");
        }
        LoginUser me = currentUser();
        String userId = me == null ? "system" : me.getId();
        String userName = me == null ? "系统" : displayName(me);
        engine.terminate(instance, "流程管理-流程终止", userId, userName);
        callHook(instance.getDefKey(), hook -> hook.onTerminal(instance.getBusinessId(), instance.getId()));
        return Result.OK("操作成功");
    }

    private List<FlowTask> activeTasks(String instId) {
        return taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("inst_id", instId)
                .eq("status", FlowTask.STATUS_ACTIVE)
                .orderByDesc("start_time"));
    }

    private LoginUser currentUser() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        return principal instanceof LoginUser ? (LoginUser) principal : null;
    }

    private String displayName(LoginUser user) {
        if (user == null) {
            return "系统";
        }
        return StringUtils.hasText(user.getRealname()) ? user.getRealname() : user.getUsername();
    }

    private void callHook(String defKey, java.util.function.Consumer<ActivitiHook> consumer) {
        if (!StringUtils.hasText(defKey) || !SpringContextHolder.containsBean(defKey)) {
            return;
        }
        try {
            consumer.accept(SpringContextHolder.getBean(defKey, ActivitiHook.class));
        } catch (Exception e) {
            log.warn("执行流程 Hook 失败: defKey={}, error={}", defKey, e.getMessage());
        }
    }
}

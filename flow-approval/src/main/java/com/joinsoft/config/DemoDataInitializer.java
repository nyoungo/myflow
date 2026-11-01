package com.joinsoft.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.ActReModelMapper;
import com.joinsoft.modules.flow.service.IActReModelService;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发环境演示数据：5 个用户、2 个已发布流程、2 条运行中实例。
 * 通过 {@code flow.demo.enabled=false} 关闭。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flow.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements CommandLineRunner {

    private final ISysUserService userService;
    private final IActReModelService modelService;
    private final ActReModelMapper defMapper;
    private final ActBusinessDataMapper instanceMapper;
    private final FlowEngine engine;

    public DemoDataInitializer(ISysUserService userService, IActReModelService modelService,
                               ActReModelMapper defMapper, ActBusinessDataMapper instanceMapper,
                               FlowEngine engine) {
        this.userService = userService;
        this.modelService = modelService;
        this.defMapper = defMapper;
        this.instanceMapper = instanceMapper;
        this.engine = engine;
    }

    @Override
    public void run(String... args) {
        try {
            initUsers();
            initProcess("leave", "请假审批", "人事");
            initProcess("expense", "费用报销", "财务");
            initInstances();
            log.info("演示数据初始化完成，账号：admin/admin123、manager/manager123、zhangsan/123456");
        } catch (Exception e) {
            log.warn("演示数据初始化失败（不影响启动）: {}", e.getMessage(), e);
        }
    }

    private void initUsers() {
        createUser("u_admin", "admin", "admin123", "系统管理员", "dept_it", ",admin,", null);
        createUser("u_manager", "manager", "manager123", "王经理", "dept_tech", ",manager,", "u_admin");
        createUser("u_zhangsan", "zhangsan", "123456", "张三", "dept_tech", ",employee,", "u_manager");
        createUser("u_lisi", "lisi", "123456", "李四", "dept_tech", ",employee,", "u_manager");
        createUser("u_finance", "finadmin", "123456", "赵会计", "dept_finance", ",finance,manager,", "u_admin");
    }

    private void createUser(String id, String username, String password, String realname, String deptId,
                            String roles, String leaderId) {
        if (userService.getByUsername(username) != null) {
            return;
        }
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(userService.encodePassword(password));
        user.setRealname(realname);
        user.setDeptId(deptId);
        user.setRoleCodes(roles);
        user.setLeaderId(leaderId);
        user.setStatus(1);
        user.setCreateTime(new Date());
        userService.save(user);
    }

    private void initProcess(String key, String name, String category) throws Exception {
        if (defMapper.selectCount(new QueryWrapper<ActReModelEntity>().eq("def_key", key)) > 0) {
            return;
        }
        String xml = loadBpmn(key);
        if (!StringUtils.hasText(xml)) {
            return;
        }
        ActReModelEntity model = new ActReModelEntity();
        model.setName(name);
        model.setKey(key);
        model.setCategory(category);
        model.setDescription(name + "（演示流程）");
        model.setBpmnXml(xml);
        String id = modelService.save(model);
        modelService.deploy(id);
        log.info("演示流程已发布: {} -> {}", key, name);
    }

    private void initInstances() {
        if (instanceMapper.selectCount(new QueryWrapper<ActBusinessData>().eq("def_key", "leave")) > 0) {
            return;
        }
        ActReModelEntity leave = latestPublished("leave");
        if (leave != null) {
            LoginUser starter = toLoginUser("u_zhangsan");
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("days", 3);
            variables.put("reason", "家中有事");
            variables.put("amount", 0);
            engine.start(leave, "leave.DEMO-LEAVE-001", "DEMO-LEAVE-001", "张三的请假申请（3 天）",
                    "/leave/DEMO-LEAVE-001", variables, starter, "");
        }
        ActReModelEntity expense = latestPublished("expense");
        if (expense != null) {
            LoginUser starter = toLoginUser("u_lisi");
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("amount", 2600);
            variables.put("reason", "客户接待");
            engine.start(expense, "expense.DEMO-EXPENSE-001", "DEMO-EXPENSE-001", "李四的费用报销（2600 元）",
                    "/expense/DEMO-EXPENSE-001", variables, starter, "");
        }
    }

    private ActReModelEntity latestPublished(String key) {
        List<ActReModelEntity> rows = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", key)
                .ge("version", 1)
                .orderByDesc("version")
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LoginUser toLoginUser(String userId) {
        SysUser user = userService.getById(userId);
        return user == null ? null : userService.toLoginUser(user);
    }

    private String loadBpmn(String key) throws Exception {
        ClassPathResource resource = new ClassPathResource("bpmn/" + key + ".bpmn20.xml");
        if (!resource.exists()) {
            return null;
        }
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}

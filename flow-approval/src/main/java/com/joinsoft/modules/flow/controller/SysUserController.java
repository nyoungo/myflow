package com.joinsoft.modules.flow.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.SecurityUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 简化用户接口，供审批人选择与管理端展示使用。
 */
@Api(value = "用户", tags = "用户")
@RestController
@RequestMapping("/sys/user")
public class SysUserController {

    private final ISysUserService userService;

    public SysUserController(ISysUserService userService) {
        this.userService = userService;
    }

    @ApiOperation(value = "用户列表", notes = "按用户名或姓名模糊查询")
    @GetMapping("/list")
    public Result<?> list(@RequestParam(required = false) String keyword) {
        QueryWrapper<SysUser> wrapper = new QueryWrapper<SysUser>()
                .eq("status", 1)
                .orderByAsc("username");
        if (StringUtils.hasText(keyword)) {
            wrapper.and(item -> item.like("username", keyword).or().like("realname", keyword));
        }
        List<SysUser> users = userService.list(wrapper);
        users.forEach(user -> user.setPassword(null));
        return Result.OK(users);
    }

    @ApiOperation(value = "当前用户", notes = "获取当前登录用户")
    @GetMapping("/current")
    public Result<?> current() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        return Result.OK(principal instanceof LoginUser ? principal : null);
    }
}

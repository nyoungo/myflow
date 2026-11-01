package com.joinsoft.config;

import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 生产环境首次启动时创建管理员账号，避免关闭演示数据后无法登录。
 * 通过 {@code flow.bootstrap-admin.enabled=false} 关闭。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flow.bootstrap-admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrap implements CommandLineRunner {

    private final ISysUserService userService;

    @Value("${flow.bootstrap-admin.username:admin}")
    private String username;

    @Value("${flow.bootstrap-admin.password:}")
    private String password;

    @Value("${flow.bootstrap-admin.realname:系统管理员}")
    private String realname;

    public AdminBootstrap(ISysUserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(String... args) {
        if (!StringUtils.hasText(username) || userService.getByUsername(username) != null) {
            return;
        }
        String initialPassword = password;
        if (!StringUtils.hasText(initialPassword)) {
            initialPassword = randomPassword();
            log.warn("未配置 flow.bootstrap-admin.password，已生成临时管理员密码：{}", initialPassword);
        }
        SysUser user = new SysUser();
        user.setId("u_admin");
        user.setUsername(username);
        user.setPassword(userService.encodePassword(initialPassword));
        user.setRealname(realname);
        user.setDeptId("dept_it");
        user.setRoleCodes(",admin,");
        user.setStatus(1);
        user.setCreateTime(new Date());
        userService.save(user);
        log.warn("已创建初始管理员账号 [{}]，请尽快登录并修改默认密码", username);
    }

    private String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder builder = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 12; i++) {
            builder.append(chars.charAt(random.nextInt(chars.length())));
        }
        return builder.toString();
    }
}

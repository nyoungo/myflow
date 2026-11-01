package com.joinsoft.auth;

import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import lombok.Data;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 登出 / 当前用户。
 */
@Service
public class AuthService {

    private final ISysUserService userService;
    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;
    private final JwtProperties properties;

    public AuthService(ISysUserService userService, JwtUtil jwtUtil, TokenStore tokenStore, JwtProperties properties) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.properties = properties;
    }

    public Map<String, Object> login(String username, String rawPassword) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(rawPassword)) {
            throw new JoinsoftException("请输入用户名和密码");
        }
        SysUser user = userService.getByUsername(username.trim());
        if (user == null || !userService.matchesPassword(user, rawPassword)) {
            throw new JoinsoftException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new JoinsoftException("用户已被禁用");
        }
        LoginUser loginUser = userService.toLoginUser(user);
        String token = jwtUtil.sign(loginUser);
        tokenStore.save(token, loginUser.getId(), properties.getExpireSeconds());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("user", loginUser);
        data.put("expiresIn", properties.getExpireSeconds());
        return data;
    }

    public void logout(String token) {
        if (StringUtils.hasText(token)) {
            tokenStore.remove(token);
        }
    }

    public LoginUser currentUser() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        return principal instanceof LoginUser ? (LoginUser) principal : null;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}

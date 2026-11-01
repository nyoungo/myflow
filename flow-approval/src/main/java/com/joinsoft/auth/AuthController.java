package com.joinsoft.auth;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.system.vo.LoginUser;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@Api(value = "认证", tags = "认证")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtProperties properties;

    public AuthController(AuthService authService, JwtProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @ApiOperation(value = "登录", notes = "用户名密码登录，返回 JWT")
    @PostMapping("/login")
    public Result<?> login(@RequestBody AuthService.LoginRequest request) {
        return Result.OK(authService.login(request.getUsername(), request.getPassword()));
    }

    @ApiOperation(value = "登出", notes = "使当前 token 失效")
    @PostMapping("/logout")
    public Result<?> logout(HttpServletRequest request) {
        String header = request.getHeader(properties.getHeader());
        String token = header;
        if (header != null && properties.getPrefix() != null && header.startsWith(properties.getPrefix())) {
            token = header.substring(properties.getPrefix().length()).trim();
        }
        authService.logout(token);
        return Result.OK("退出成功");
    }

    @ApiOperation(value = "当前用户", notes = "获取当前登录用户信息")
    @GetMapping("/userInfo")
    public Result<?> userInfo() {
        LoginUser user = authService.currentUser();
        return Result.OK(user);
    }

    @ApiOperation(value = "未认证", notes = "未认证时的统一返回")
    @GetMapping("/unauthorized")
    public Result<?> unauthorized() {
        return Result.error(401, "未登录或登录已过期");
    }

    @ApiOperation(value = "校验 token", notes = "调试用")
    @GetMapping("/check")
    public Result<?> check(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Result.OK(authorization != null);
    }
}

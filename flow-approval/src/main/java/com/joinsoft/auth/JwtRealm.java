package com.joinsoft.auth;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.service.ISysUserService;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

/**
 * JWT Realm：校验 token 并加载用户、角色。
 */
public class JwtRealm extends AuthorizingRealm {

    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;
    private final ISysUserService userService;

    public JwtRealm(JwtUtil jwtUtil, TokenStore tokenStore, ISysUserService userService) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.userService = userService;
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtToken;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        Object principal = principals.getPrimaryPrincipal();
        if (!(principal instanceof LoginUser)) {
            return new SimpleAuthorizationInfo();
        }
        LoginUser loginUser = (LoginUser) principal;
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        info.addRoles(loginUser.getRoleCodeList());
        return info;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String jwt = String.valueOf(token.getPrincipal());
        DecodedJWT decoded;
        try {
            decoded = jwtUtil.verify(jwt);
        } catch (Exception e) {
            throw new IncorrectCredentialsException("登录凭证无效或已过期");
        }
        String storedUserId = tokenStore.findUserId(jwt);
        if (storedUserId == null || !storedUserId.equals(decoded.getSubject())) {
            throw new IncorrectCredentialsException("登录已失效，请重新登录");
        }
        SysUser user = userService.getById(storedUserId);
        if (user == null) {
            throw new UnknownAccountException("用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new AuthenticationException("用户已被禁用");
        }
        LoginUser loginUser = userService.toLoginUser(user);
        return new SimpleAuthenticationInfo(loginUser, jwt, getName());
    }
}

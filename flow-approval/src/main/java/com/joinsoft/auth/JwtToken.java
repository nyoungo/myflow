package com.joinsoft.auth;

import org.apache.shiro.authc.AuthenticationToken;

/**
 * Shiro 认证令牌，仅承载 JWT 字符串。
 */
public class JwtToken implements AuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String token;

    public JwtToken(String token) {
        this.token = token;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    public String getToken() {
        return token;
    }
}

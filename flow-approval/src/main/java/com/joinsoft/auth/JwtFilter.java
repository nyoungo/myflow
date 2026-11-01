package com.joinsoft.auth;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.util.JsonUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.web.filter.AccessControlFilter;
import org.springframework.util.StringUtils;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 无状态 JWT 过滤器：解析 token，绑定 Shiro Subject。
 */
public class JwtFilter extends AccessControlFilter {

    private final JwtUtil jwtUtil;
    private final TokenStore tokenStore;
    private final JwtProperties properties;

    public JwtFilter(JwtUtil jwtUtil, TokenStore tokenStore, JwtProperties properties) {
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.properties = properties;
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            return true;
        }
        String token = resolveToken(httpRequest);
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            jwtUtil.verify(token);
        } catch (Exception e) {
            return false;
        }
        if (tokenStore.findUserId(token) == null) {
            return false;
        }
        try {
            getSubject(request, response).login(new JwtToken(token));
            return true;
        } catch (AuthenticationException e) {
            return false;
        }
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.setContentType("application/json;charset=UTF-8");
        Result<Object> result = Result.error(401, "未登录或登录已过期");
        httpResponse.getWriter().write(JsonUtils.toJson(result));
        httpResponse.getWriter().flush();
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(properties.getHeader());
        if (StringUtils.hasText(header)) {
            if (StringUtils.hasText(properties.getPrefix()) && header.startsWith(properties.getPrefix())) {
                return header.substring(properties.getPrefix().length()).trim();
            }
            return header.trim();
        }
        return request.getParameter(properties.getQueryParam());
    }
}

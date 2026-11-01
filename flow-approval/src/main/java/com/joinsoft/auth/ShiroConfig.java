package com.joinsoft.auth;

import com.joinsoft.modules.system.service.ISysUserService;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.DefaultWebSubjectFactory;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.servlet.Filter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shiro 配置：JWT 无状态认证。
 */
@Configuration
public class ShiroConfig {

    @Bean
    public LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    public JwtRealm jwtRealm(JwtUtil jwtUtil, TokenStore tokenStore, ISysUserService userService) {
        return new JwtRealm(jwtUtil, tokenStore, userService);
    }

    @Bean
    public DefaultWebSecurityManager securityManager(JwtRealm jwtRealm) {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        securityManager.setRealm(jwtRealm);

        DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();
        sessionManager.setSessionIdUrlRewritingEnabled(false);
        securityManager.setSessionManager(sessionManager);

        DefaultSessionStorageEvaluator evaluator = new DefaultSessionStorageEvaluator();
        evaluator.setSessionStorageEnabled(false);
        DefaultSubjectDAO subjectDAO = new DefaultSubjectDAO();
        subjectDAO.setSessionStorageEvaluator(evaluator);
        securityManager.setSubjectDAO(subjectDAO);

        securityManager.setSubjectFactory(new DefaultWebSubjectFactory() {
            @Override
            public Subject createSubject(SubjectContext context) {
                context.setSessionCreationEnabled(false);
                return super.createSubject(context);
            }
        });
        return securityManager;
    }

    @Bean
    public JwtFilter jwtFilter(JwtUtil jwtUtil, TokenStore tokenStore, JwtProperties properties) {
        return new JwtFilter(jwtUtil, tokenStore, properties);
    }

    /**
     * JwtFilter 作为普通 Filter Bean 会被 Spring Boot 自动注册到 /*，
     * 导致 Shiro 的 anon 规则失效；此处关闭自动注册，仅由 Shiro 过滤器链使用。
     */
    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtFilter jwtFilter) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(jwtFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @DependsOn("lifecycleBeanPostProcessor")
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager, JwtFilter jwtFilter) {
        ShiroFilterFactoryBean factoryBean = new ShiroFilterFactoryBean();
        factoryBean.setSecurityManager(securityManager);

        Map<String, Filter> filters = new HashMap<>();
        filters.put("jwt", jwtFilter);
        factoryBean.setFilters(filters);

        Map<String, String> chain = new LinkedHashMap<>();
        chain.put("/auth/login", "anon");
        chain.put("/auth/logout", "anon");
        chain.put("/auth/unauthorized", "anon");
        chain.put("/error", "anon");
        chain.put("/doc.html", "anon");
        chain.put("/swagger-ui.html", "anon");
        chain.put("/swagger-ui/**", "anon");
        chain.put("/swagger-resources/**", "anon");
        chain.put("/v3/api-docs/**", "anon");
        chain.put("/webjars/**", "anon");
        chain.put("/favicon.ico", "anon");
        chain.put("/h2-console/**", "anon");
        chain.put("/", "anon");
        chain.put("/index.html", "anon");
        chain.put("/assets/**", "anon");
        chain.put("/css/**", "anon");
        chain.put("/js/**", "anon");
        chain.put("/img/**", "anon");
        chain.put("/fonts/**", "anon");
        chain.put("/static/**", "anon");
        chain.put("/**", "jwt");
        factoryBean.setFilterChainDefinitionMap(chain);
        factoryBean.setLoginUrl("/auth/unauthorized");
        factoryBean.setUnauthorizedUrl("/auth/unauthorized");
        return factoryBean;
    }
}

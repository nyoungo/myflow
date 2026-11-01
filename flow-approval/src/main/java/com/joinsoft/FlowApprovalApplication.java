package com.joinsoft;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import springfox.documentation.oas.annotations.EnableOpenApi;

/**
 * 轻量级工作流审批平台启动类。
 *
 * <p>兼容 joinsoft-boot-module-flow 的 REST 接口与 FlowUtil，不依赖 Activiti。</p>
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableOpenApi
@MapperScan("com.joinsoft.modules.**.mapper")
public class FlowApprovalApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowApprovalApplication.class, args);
    }
}

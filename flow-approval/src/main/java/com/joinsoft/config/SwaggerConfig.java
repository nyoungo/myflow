package com.joinsoft.config;

import io.swagger.annotations.ApiOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

@Configuration
public class SwaggerConfig {

    private ApiInfoBuilder apiInfo() {
        return new ApiInfoBuilder()
                .title("flow-approval 工作流审批平台 API")
                .description("兼容 joinsoft-boot-module-flow 的轻量工作流引擎接口文档")
                .version("1.0.0");
    }

    @Bean
    public Docket docket() {
        return new Docket(DocumentationType.OAS_30)
                .apiInfo(apiInfo().build())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.joinsoft"))
                .apis(RequestHandlerSelectors.withMethodAnnotation(ApiOperation.class))
                .paths(PathSelectors.any())
                .build();
    }
}

package com.joinsoft.common.aspect.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解，兼容原项目用法。当前实现仅记录 debug 日志。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AutoLog {

    String value() default "";

    String logType() default "";

    boolean saveRequestData() default true;
}

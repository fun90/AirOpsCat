package com.fun90.airopscat.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记转换策略支持的协议类型
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SupportedProtocols {
    /**
     * 支持的协议列表
     */
    String[] value();
    
    /**
     * 策略优先级，数值越小优先级越高
     */
    int priority() default Integer.MAX_VALUE;
    
    /**
     * 策略描述
     */
    String description() default "";
}
package com.fun90.airopscat.util;

import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;

/**
 * CDI注解发现工具类
 * 处理CDI代理类的注解获取问题
 */
@Slf4j
public final class CdiAnnotationUtils {
    
    private CdiAnnotationUtils() {
        // 工具类不允许实例化
    }
    
    /**
     * 从策略对象中查找指定注解
     * 支持CDI代理类，会查找实际的实现类
     */
    public static <T extends Annotation> T findAnnotation(Object strategy, Class<T> annotationClass) {
        Class<?> clazz = strategy.getClass();
        
        // 首先尝试从当前类获取注解
        T annotation = clazz.getAnnotation(annotationClass);
        if (annotation != null) {
            log.debug("从类 {} 直接获取到 @{} 注解", clazz.getSimpleName(), annotationClass.getSimpleName());
            return annotation;
        }
        
        // 如果当前类没有注解，可能是CDI代理类，需要查找实际的实现类
        Class<?> actualClass = findActualImplementationClass(clazz);
        if (actualClass != null && actualClass != clazz) {
            annotation = actualClass.getAnnotation(annotationClass);
            if (annotation != null) {
                log.debug("从实际实现类 {} 获取到 @{} 注解（代理类: {}）", 
                    actualClass.getSimpleName(), annotationClass.getSimpleName(), clazz.getSimpleName());
                return annotation;
            }
        }
        
        // 最后尝试从所有超类和接口中查找
        annotation = findAnnotationInHierarchy(clazz, annotationClass);
        if (annotation != null) {
            log.debug("从类层次结构中获取到 @{} 注解，策略类: {}", annotationClass.getSimpleName(), clazz.getSimpleName());
            return annotation;
        }
        
        log.debug("无法在策略 {} 及其层次结构中找到 @{} 注解", clazz.getSimpleName(), annotationClass.getSimpleName());
        return null;
    }
    
    /**
     * 查找CDI代理类背后的实际实现类
     */
    private static Class<?> findActualImplementationClass(Class<?> proxyClass) {
        String className = proxyClass.getName();
        
        // CDI代理类通常以特定后缀结尾或包含特定标识
        if (className.contains("$Proxy") || className.contains("$$") || 
            className.contains("_ClientProxy") || className.contains("_Subclass")) {
            
            // 尝试通过反射找到实际的类
            try {
                // 检查是否有 getSuperclass() 方法返回实际类
                Class<?> superClass = proxyClass.getSuperclass();
                if (superClass != null && !superClass.equals(Object.class)) {
                    return superClass;
                }
                
                // 尝试查找接口的实现类
                Class<?>[] interfaces = proxyClass.getInterfaces();
                for (Class<?> iface : interfaces) {
                    // 通过类名推断实际实现类
                    String implClassName = inferImplementationClassName(className);
                    if (implClassName != null) {
                        try {
                            return Class.forName(implClassName);
                        } catch (ClassNotFoundException e) {
                            log.debug("无法加载推断的实现类: {}", implClassName);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("查找实际实现类时发生异常: {}", e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 从代理类名推断实际实现类名
     */
    private static String inferImplementationClassName(String proxyClassName) {
        // 移除CDI代理类的后缀
        String className = proxyClassName;
        
        // 常见的CDI代理类模式
        if (className.contains("$Proxy")) {
            className = className.substring(0, className.indexOf("$Proxy"));
        } else if (className.contains("$$")) {
            className = className.substring(0, className.indexOf("$$"));
        } else if (className.contains("_ClientProxy")) {
            className = className.replace("_ClientProxy", "");
        } else if (className.contains("_Subclass")) {
            className = className.replace("_Subclass", "");
        }
        
        return className.equals(proxyClassName) ? null : className;
    }
    
    /**
     * 在类层次结构中查找注解
     */
    private static <T extends Annotation> T findAnnotationInHierarchy(Class<?> clazz, Class<T> annotationClass) {
        // 检查当前类
        T annotation = clazz.getAnnotation(annotationClass);
        if (annotation != null) {
            return annotation;
        }
        
        // 检查超类
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            annotation = findAnnotationInHierarchy(superClass, annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        
        // 检查接口
        for (Class<?> iface : clazz.getInterfaces()) {
            annotation = findAnnotationInHierarchy(iface, annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        
        return null;
    }
}
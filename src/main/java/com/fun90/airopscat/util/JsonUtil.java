package com.fun90.airopscat.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;

public class JsonUtil {
    private JsonUtil() {
        throw new IllegalStateException("Utility class");
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
            // 注册 JavaTimeModule 用于处理 ZonedDateTime 等 java.time 类
            .registerModule(new JavaTimeModule())
            //取消默认转换timestamps形式
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            //忽略空Bean转json的错误
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            //反序列化
            //忽略 在json字符串中存在，但是在java对象中不存在对应属性的情况。防止错误
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 完成对象序列化为字符串
     *
     * @param obj 源对象
     * @param <T>
     * @return
     */
    public static <T> String toJsonString(T obj) {
        if (obj == null) {
            return null;
        }
        try {
            return obj instanceof String ? (String) obj : objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /**
     * 完成字符串反序列化为对象
     *
     * @param str   源字符串
     * @param clazz 目标对象的Class
     * @param <T>
     * @return
     */
    public static <T> T toObject(String str, Class<T> clazz) {
        try {
            return (clazz == String.class) ? (T) str : objectMapper.readValue(str, clazz);
        } catch (IOException e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }

    //jackson在反序列化时，如果传入List，会自动反序列化为LinkedHashMap的List
    //所以重载一下方法，解决之前String2Obj无法解决的问题

}

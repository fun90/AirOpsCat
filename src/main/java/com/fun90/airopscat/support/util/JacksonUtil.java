package com.fun90.airopscat.support.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.json.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JacksonUtil {
    private JacksonUtil() {
        throw new IllegalStateException("Utility class");
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
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
            throw new JsonParseException(e);
        }
    }

    public static <T> byte[] toJsonBytes(T obj) {
        return toJsonString(obj)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 完成对象序列化为字符串，但是字符串会保证一定的结构性（提高可读性，增加字符串大小）
     *
     * @param obj 源对象
     * @param <T>
     * @return
     */
    public static <T> String toJsonStringPretty(T obj) {
        if (obj == null) {
            return null;
        }
        try {
            return obj instanceof String ? (String) obj : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            throw new JsonParseException(e);
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
            throw new JsonParseException(e);
        }
    }

    //jackson在反序列化时，如果传入List，会自动反序列化为LinkedHashMap的List
    //所以重载一下方法，解决之前String2Obj无法解决的问题

    /**
     * 进行复杂类型反序列化工作 （自定义类型的集合类型）
     *
     * @param str           源字符串
     * @param typeReference 包含elementType与CollectionType的typeReference
     * @param <T>
     * @return
     */
    public static <T> T toObject(String str, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(str, typeReference);
        } catch (IOException e) {
            throw new JsonParseException(e);
        }
    }

    /**
     * 进行复杂类型反序列化工作（可变类型数量的）
     *
     * @param str             需要进行反序列化的字符串
     * @param collectionClass 需要反序列化的集合类型 由于这里的类型未定，且为了防止与返回值类型T冲突，故采用<?>表示泛型
     * @param elementClasses  集合中的元素类型（可多个）   此处同上通过<?>...表示多个未知泛型
     * @param <T>             返回值的泛型类型是由javatype获取的
     * @return
     */
    public static <T> T toObject(String str, Class<?> collectionClass, Class<?>... elementClasses) {
        JavaType javaType = objectMapper.getTypeFactory().constructParametricType(collectionClass, elementClasses);
        try {
            return objectMapper.readValue(str, javaType);
        } catch (IOException e) {
            throw new JsonParseException(e);
        }
    }

    public static JsonNode toJsonNode(String str) {
        try {
            return objectMapper.readTree(str);
        } catch (IOException e) {
            throw new JsonParseException(e);
        }
    }

    public static <T> T toObject(JsonNode node, Class<T> clazz) {
        try {
            return objectMapper.treeToValue(node, clazz);
        } catch (IOException e) {
            throw new JsonParseException(e);
        }
    }
}

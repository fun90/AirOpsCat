package com.fun90.airopscat.utils;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

public class RawJsonDeserializer extends JsonDeserializer<String> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String deserialize(com.fasterxml.jackson.core.JsonParser p, DeserializationContext ctxt) throws IOException {
        // 读取整个 JSON 树并转换成字符串
        return mapper.writeValueAsString(p.readValueAsTree());
    }
}

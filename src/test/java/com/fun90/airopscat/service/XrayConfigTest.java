package com.fun90.airopscat.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fun90.airopscat.model.dto.xray.InboundConfig;
import com.fun90.airopscat.model.dto.xray.XrayConfig;
import com.fun90.airopscat.model.dto.xray.setting.inbound.VlessInboundSetting;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Slf4j
public class XrayConfigTest {
    private static final ObjectMapper objectMapper = new ObjectMapper()
            //取消默认转换timestamps形式
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            //忽略空Bean转json的错误
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            //反序列化
            //忽略 在json字符串中存在，但是在java对象中不存在对应属性的情况。防止错误
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    public void test() throws IOException {
        XrayConfig xrayConfig = objectMapper.readValue(new File("/home/alex/文档/tmp.json"), XrayConfig.class);

        // 反序列化
        List<InboundConfig> inbounds = xrayConfig.getInbounds();
        InboundConfig config = inbounds.get(1);
        log.info("反序列化成功，协议: {}", config.getProtocol());
        log.info("Settings类型: {}", config.getSettings().getClass().getSimpleName());

        // 验证是否为正确的类型
        if (config.getSettings() instanceof VlessInboundSetting) {
            VlessInboundSetting vlessSettings = (VlessInboundSetting) config.getSettings();
            log.info("VLESS客户端数量: {}", vlessSettings.getClients().size());
        }

        // 序列化回JSON
        String serializedJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(config);
        log.info("序列化结果:\n{}", serializedJson);
    }

}

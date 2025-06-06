package com.fun90.airopscat.model.convert;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
public class NodeConverter {

    public static NodeDto toDto(Node node) {
        NodeDto dto = new NodeDto();
        BeanUtils.copyProperties(node, dto);

        // 设置类型描述
        dto.setTypeDescription(node.getTypeDescription());

        // 获取服务器信息
        dto.setServerIp(node.getServer().getIp());
        dto.setServerHost(node.getServer().getHost());

        // 设置出站信息
        if (node.getOutNode() != null) {
            dto.setOutName(node.getOutNode().getName());
            dto.setOutPort(node.getOutNode().getPort());
            dto.setOutServerHost(node.getOutNode().getServer().getHost());
        }

        // 转换JSON配置
        if (StringUtils.hasText(node.getInbound())) {
            dto.setInbound(JsonUtil.toObject(node.getInbound(), Map.class));
        }

        if (StringUtils.hasText(node.getRule())) {
            dto.setRule(JsonUtil.toObject(node.getRule(), Map.class));
        }

        return dto;
    }
}

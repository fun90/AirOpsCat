package com.fun90.airopscat.model.convert;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class NodeConverter {

    public static NodeDto toDto(Node node) {
        NodeDto dto = new NodeDto();
        
        // Manual property copying instead of BeanUtils
        dto.setId(node.getId());
        dto.setName(node.getName());
        dto.setType(node.getType());
        dto.setPort(node.getPort());
        dto.setProtocol(node.getProtocol());
        dto.setServerId(node.getServerId());
        dto.setOutId(node.getOutId());
        dto.setLevel(node.getLevel());
        dto.setDeployed(node.getDeployed());
        dto.setDisabled(node.getDisabled());
        dto.setRemark(node.getRemark());
        dto.setCreateTime(node.getCreateTime());
        dto.setUpdateTime(node.getUpdateTime());

        // 设置类型描述
        dto.setTypeDescription(node.getTypeDescription());

        // 获取服务器信息
        if (node.getServer() != null) {
            dto.setServerIp(node.getServer().getIp());
            dto.setServerHost(node.getServer().getHost());
        }

        // 设置出站信息
        if (node.getOutNode() != null) {
            dto.setOutName(node.getOutNode().getName());
            dto.setOutPort(node.getOutNode().getPort());
            if (node.getOutNode().getServer() != null) {
                dto.setOutServerHost(node.getOutNode().getServer().getHost());
            }
        }

        // 转换JSON配置
        if (node.getInbound() != null && !node.getInbound().trim().isEmpty()) {
            dto.setInbound(JsonUtil.toObject(node.getInbound(), Map.class));
        }

        if (node.getRule() != null && !node.getRule().trim().isEmpty()) {
            dto.setRule(JsonUtil.toObject(node.getRule(), Map.class));
        }

        if (node.getTags() != null) {
            dto.setTags(node.getTags());
        }

        return dto;
    }
}

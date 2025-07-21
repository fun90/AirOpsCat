package com.fun90.airopscat.model.convert;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.util.JsonUtil;
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

        // 获取服务器信息 - 使用安全的方式访问避免LazyInitializationException
        try {
            if (node.getServer() != null) {
                dto.setServerIp(node.getServer().getIp());
                dto.setServerHost(node.getServer().getHost());
            }
        } catch (org.hibernate.LazyInitializationException e) {
            // 当Hibernate session关闭时，优雅地处理懒加载异常
            log.debug("Server lazy loading failed for node {}, setting basic info only", node.getId());
            // 只设置基本的serverId信息，不设置服务器详细信息
            // dto.setServerId() 在上面已经设置了
        }

        // 设置出站信息 - 使用安全的方式访问避免LazyInitializationException
        try {
            if (node.getOutNode() != null) {
                dto.setOutName(node.getOutNode().getName());
                dto.setOutPort(node.getOutNode().getPort());
                if (node.getOutNode().getServer() != null) {
                    dto.setOutServerHost(node.getOutNode().getServer().getHost());
                }
            }
        } catch (org.hibernate.LazyInitializationException e) {
            // 当Hibernate session关闭时，优雅地处理懒加载异常
            log.debug("OutNode lazy loading failed for node {}, setting basic info only", node.getId());
            // 只设置基本的outId信息，不设置名称等需要懒加载的字段
            // dto.setOutId() 在上面已经设置了
        }

        // 转换JSON配置
        if (node.getInbound() != null && !node.getInbound().trim().isEmpty()) {
            dto.setInbound(JsonUtil.toObject(node.getInbound(), Map.class));
        }

        if (node.getRule() != null && !node.getRule().trim().isEmpty()) {
            dto.setRule(JsonUtil.toObject(node.getRule(), Map.class));
        }

        // 设置标签信息 - 使用安全的方式访问避免LazyInitializationException
        try {
            if (node.getTags() != null) {
                dto.setTags(node.getTags());
            }
        } catch (org.hibernate.LazyInitializationException e) {
            // 当Hibernate session关闭时，优雅地处理懒加载异常
            log.debug("Tags lazy loading failed for node {}, skipping tags", node.getId());
            // 不设置标签信息
        }

        return dto;
    }
}

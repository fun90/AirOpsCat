package com.fun90.airopscat.model.convert;

import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.NodeRequest;
import com.fun90.airopscat.model.dto.NodeServerDto;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.DomainDnsRecord;
import com.fun90.airopscat.model.enums.DnsRecordStatus;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.util.JsonUtil;
import com.fun90.airopscat.util.NodeName;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class NodeConverter {

    public static NodeDto toDto(Node node, List<NodeServer> placements, List<NodeServer> outPlacements) {
        NodeDto dto = new NodeDto();
        
        // Manual property copying instead of BeanUtils
        dto.setId(node.getId());
        dto.setName(NodeName.withNumber(node.getName()));
        dto.setType(node.getType());
        dto.setPort(node.getPort());
        dto.setProtocol(node.getProtocol());
        dto.setCoreType(node.getCoreType());
        dto.setOutId(node.getOutId());
        dto.setLevel(node.getLevel());
        dto.setDeployed(NodeServerRepository.aggregateStatus(placements));
        dto.setDisabled(node.getDisabled());
        dto.setRemark(node.getRemark());
        dto.setCreateTime(node.getCreateTime());
        dto.setUpdateTime(node.getUpdateTime());
        dto.setRegion(node.getRegion());

        // 设置类型描述
        dto.setTypeDescription(node.getTypeDescription());

        List<NodeServer> sortedPlacements = placements == null ? List.of()
                : placements.stream().sorted(NodeServerRepository.PRIORITY_ORDER).toList();
        dto.setPlacements(sortedPlacements.stream().map(NodeConverter::toPlacementDto).toList());
        NodeServerRepository.resolveEffective(sortedPlacements)
                .or(() -> sortedPlacements.stream().findFirst())
                .ifPresent(placement -> {
            dto.setNodeServerId(placement.getId());
            dto.setServerId(placement.getServerId());
            dto.setAccessHostId(placement.getAccessHostId());
            dto.setAccessHost(placement.getAccessHost() != null ? placement.getAccessHost().getFullName() : null);
            if (placement.getServer() != null) {
                dto.setServerIp(placement.getServer().getIp());
                dto.setServerHost(resolveEffectiveHost(placement.getAccessHost(), placement.getServer()));
            }
        });

        // 设置出站信息 - 使用安全的方式访问避免LazyInitializationException
        try {
            if (node.getOutNode() != null) {
                dto.setOutName(node.getOutNode().getName());
                dto.setOutPort(node.getOutNode().getPort());
                NodeServerRepository.resolveEffective(outPlacements)
                        .filter(placement -> placement.getServer() != null)
                        .ifPresent(placement -> dto.setOutServerHost(
                                resolveEffectiveHost(placement.getAccessHost(), placement.getServer())));
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

    /**
     * 将 NodeRequest 转换为 Node 实体
     * @param request NodeRequest 对象
     * @return Node 实体
     */
    public static Node fromRequest(NodeRequest request) {
        Node node = new Node();
        
        if (request.getId() != null) {
            node.setId(request.getId());
        }
        node.setPort(request.getPort());
        node.setProtocol(request.getProtocol());
        node.setCoreType(request.getCoreType());
        node.setType(request.getType());
        node.setInbound(request.getInbound() != null ? 
            JsonUtil.toJsonString(request.getInbound()) : null);
        node.setOutId(request.getOutId());
        node.setRule(request.getRule() != null ? 
            JsonUtil.toJsonString(request.getRule()) : null);
        node.setLevel(request.getLevel());
        node.setDisabled(request.getDisabled());
        node.setName(request.getName());
        node.setRemark(request.getRemark());
        node.setRegion(request.getRegion() == null || request.getRegion().isBlank() ? null : request.getRegion().trim());

        return node;
    }

    public static NodeServerDto toPlacementDto(NodeServer placement) {
        NodeServerDto dto = new NodeServerDto();
        dto.setId(placement.getId());
        dto.setNodeId(placement.getNodeId());
        dto.setServerId(placement.getServerId());
        dto.setAccessHostId(placement.getAccessHostId());
        dto.setExposed(placement.getExposed());
        dto.setPriority(placement.getPriority());
        dto.setCheapStart(placement.getCheapStart());
        dto.setCheapEnd(placement.getCheapEnd());
        dto.setDeployed(placement.getDeployed());
        if (placement.getServer() != null) {
            dto.setServerName(placement.getServer().getName());
            dto.setServerIp(placement.getServer().getIp());
            dto.setServerHost(resolveEffectiveHost(placement.getAccessHost(), placement.getServer()));
        }
        if (placement.getAccessHost() != null) {
            dto.setAccessHost(placement.getAccessHost().getFullName());
            dto.setAccessHostStatus(String.valueOf(placement.getAccessHost().getStatus()));
        }
        return dto;
    }

    /**
     * 订阅 host：域名记录已同步到 DNS 服务商时用域名，否则（未选域名、尚未推送成功）用服务器 IP。
     */
    public static String resolveEffectiveHost(DomainDnsRecord accessHost, Server server) {
        if (accessHost != null && accessHost.getStatus() == DnsRecordStatus.SYNCED
                && accessHost.getFullName() != null && !accessHost.getFullName().isBlank()) {
            return accessHost.getFullName();
        }
        return server == null ? null : server.getIp();
    }

    /**
     * 将 NodeRequest 转换为 Node 实体，并设置指定的 ID
     * @param request NodeRequest 对象
     * @param id 要设置的 ID
     * @return Node 实体
     */
    public static Node fromRequest(NodeRequest request, Long id) {
        Node node = fromRequest(request);
        node.setId(id);
        return node;
    }
}

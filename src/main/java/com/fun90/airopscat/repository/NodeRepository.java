package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class NodeRepository implements PanacheRepository<Node> {

    public List<Node> findByServerId(Long serverId) {
        if (serverId == null) {
            return List.of();
        }
        return find("id in (select ns.nodeId from NodeServer ns where ns.serverId = ?1)", serverId).list();
    }

    public List<Node> findOnlineTrackableByServerIp(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            return List.of();
        }
        return find("type = 0 and (disabled is null or disabled = 0) and id in ("
                        + "select ns.nodeId from NodeServer ns where ns.server.ip = ?1 "
                        + "and (ns.enabled is null or ns.enabled = 1) and ns.deployed = ?2)",
                serverIp.trim(), NodeDeploymentStatus.DEPLOYED.getValue()).list();
    }

    public List<Node> findByIdIn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("id in ?1", ids).list();
    }

    public List<Node> findByType(Integer type) {
        return find("type", type).list();
    }

    public List<Node> findByOutId(Long outId) {
        if (outId == null) {
            return List.of();
        }
        return find("outId", outId).list();
    }

    public boolean existsByDnsRecordIdAndIdNot(Long dnsRecordId, Long id) {
        if (dnsRecordId == null) {
            return false;
        }
        return count("dnsRecordId = ?1 and id != ?2", dnsRecordId, id == null ? -1L : id) > 0;
    }

    public long fillEmptyCoreType(String coreType) {
        return update("coreType = ?1 where coreType is null or trim(coreType) = ''", coreType);
    }

    public long countProxyNodes() {
        return count("type = 0");
    }
    
    public long countLandingNodes() {
        return count("type = 1");
    }
    
    public long countActiveNodes() {
        return count("disabled = 0");
    }
    
    public long countDisabledNodes() {
        return count("disabled = 1");
    }

    // 检查名称和编号是否已被使用
    public boolean existsByNameAndNoAndIdNot(String name, Integer no, Long id) {
        return count("name = ?1 and no = ?2 and id != ?3", name, no, id) > 0;
    }
}

package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.RouteRule;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class RouteRuleRepository implements PanacheRepository<RouteRule> {

    public List<RouteRule> findEnabledByServerIds(List<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return List.of();
        }
        return find("select distinct rr from RouteRule rr join rr.servers s " +
                        "where rr.enabled = 1 and s.id in ?1 and lower(trim(rr.coreType)) = 'sing-box'",
                serverIds)
                .list();
    }

    public long countByEnabled(Integer enabled) {
        return count("enabled", enabled);
    }

    public List<RouteRule> findByOutboundNodeIds(List<Long> outboundNodeIds) {
        if (outboundNodeIds == null || outboundNodeIds.isEmpty()) {
            return List.of();
        }
        return find("outboundNodeId in ?1", outboundNodeIds).list();
    }

    public List<RouteRule> findByServerId(Long serverId) {
        if (serverId == null) {
            return List.of();
        }
        return find("select distinct rr from RouteRule rr join rr.servers s where s.id = ?1", serverId).list();
    }
}

package com.fun90.airopscat.repository;

import com.fun90.airopscat.model.entity.ServerHost;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ServerHostRepository implements PanacheRepository<ServerHost> {

    public List<ServerHost> findByServerId(Long serverId) {
        return find("serverId", Sort.by("sort").ascending().and("id").ascending(), serverId).list();
    }

    public List<ServerHost> findByServerIdIn(List<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return List.of();
        }
        return find("serverId in ?1", Sort.by("serverId").ascending().and("sort").ascending().and("id").ascending(), serverIds).list();
    }

    public ServerHost findPrimaryByServerId(Long serverId) {
        return find("serverId = ?1 and isPrimary = 1", Sort.by("sort").ascending().and("id").ascending(), serverId).firstResult();
    }

    public long deleteByServerId(Long serverId) {
        return delete("serverId", serverId);
    }
}

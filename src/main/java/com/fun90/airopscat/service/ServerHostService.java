package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.ServerHostDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerHost;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.ServerRepository;
import com.fun90.airopscat.repository.ServerHostRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class ServerHostService {

    private final ServerHostRepository serverHostRepository;
    private final ServerRepository serverRepository;
    private final NodeRepository nodeRepository;

    @Inject
    public ServerHostService(ServerHostRepository serverHostRepository,
                             ServerRepository serverRepository,
                             NodeRepository nodeRepository) {
        this.serverHostRepository = serverHostRepository;
        this.serverRepository = serverRepository;
        this.nodeRepository = nodeRepository;
    }

    public List<ServerHost> getHostsByServerId(Long serverId) {
        if (serverId == null) {
            return List.of();
        }
        List<ServerHost> hosts = serverHostRepository.findByServerId(serverId);
        if (!hosts.isEmpty()) {
            return hosts;
        }
        return List.of();
    }

    public Map<Long, List<ServerHost>> getHostsByServerIds(List<Long> serverIds) {
        Map<Long, List<ServerHost>> result = new LinkedHashMap<>();
        for (ServerHost host : serverHostRepository.findByServerIdIn(serverIds)) {
            result.computeIfAbsent(host.getServerId(), ignored -> new ArrayList<>()).add(host);
        }
        return result;
    }

    public ServerHost getHostById(Long id) {
        if (id == null) {
            return null;
        }
        return serverHostRepository.findById(id);
    }

    public String resolvePrimaryHost(Server server) {
        if (server == null) {
            return null;
        }
        ServerHost primary = serverHostRepository.findPrimaryByServerId(server.getId());
        if (primary != null && primary.getHost() != null && !primary.getHost().isBlank()) {
            return primary.getHost().trim();
        }
        if (server.getHost() != null && !server.getHost().isBlank()) {
            return server.getHost().trim();
        }
        return null;
    }

    public List<ServerHostDto> toDtos(List<ServerHost> hosts, Server server) {
        List<ServerHostDto> dtos = new ArrayList<>();
        if (hosts != null) {
            for (ServerHost host : hosts) {
                dtos.add(toDto(host));
            }
        }

        if (!dtos.isEmpty()) {
            return dtos;
        }

        if (server != null && server.getHost() != null && !server.getHost().isBlank()) {
            ServerHostDto fallback = new ServerHostDto();
            fallback.setServerId(server.getId());
            fallback.setHost(server.getHost().trim());
            fallback.setIsPrimary(1);
            fallback.setEnabled(1);
            fallback.setSort(0);
            dtos.add(fallback);
        }
        return dtos;
    }

    public ServerHostDto toDto(ServerHost host) {
        ServerHostDto dto = new ServerHostDto();
        dto.setId(host.getId());
        dto.setServerId(host.getServerId());
        dto.setHost(host.getHost());
        dto.setIsPrimary(host.getIsPrimary());
        dto.setEnabled(host.getEnabled());
        dto.setSort(host.getSort());
        dto.setDomainId(host.getDomainId());
        dto.setRemark(host.getRemark());
        dto.setCreateTime(host.getCreateTime());
        dto.setUpdateTime(host.getUpdateTime());
        return dto;
    }

    @Transactional
    public int backfillPrimaryHosts() {
        int createdCount = 0;
        for (Server server : serverRepository.listAll()) {
            if (server == null || server.getId() == null || server.getHost() == null || server.getHost().isBlank()) {
                continue;
            }
            if (serverHostRepository.findPrimaryByServerId(server.getId()) != null) {
                continue;
            }

            ServerHost host = new ServerHost();
            host.setServerId(server.getId());
            host.setHost(server.getHost().trim());
            host.setIsPrimary(1);
            host.setEnabled(1);
            host.setSort(0);
            serverHostRepository.persist(host);
            createdCount++;
        }
        return createdCount;
    }

    @Transactional
    public List<ServerHost> syncHosts(Server server, List<ServerHostDto> hostDtos, String fallbackHost) {
        if (server == null || server.getId() == null) {
            return List.of();
        }

        List<NormalizedHost> normalizedHosts = normalizeHosts(hostDtos, fallbackHost);
        List<ServerHost> existingHosts = serverHostRepository.findByServerId(server.getId());
        Map<Long, ServerHost> existingById = new LinkedHashMap<>();
        Map<String, ServerHost> existingByHost = new LinkedHashMap<>();
        for (ServerHost existingHost : existingHosts) {
            existingById.put(existingHost.getId(), existingHost);
            existingByHost.putIfAbsent(existingHost.getHost().trim().toLowerCase(), existingHost);
        }

        List<Long> retainedIds = new ArrayList<>();
        List<ServerHost> persistedHosts = new ArrayList<>();
        for (int i = 0; i < normalizedHosts.size(); i++) {
            NormalizedHost normalizedHost = normalizedHosts.get(i);
            ServerHost host = resolveExistingHost(existingById, existingByHost, normalizedHost);
            boolean isNewHost = host == null;
            if (isNewHost) {
                host = new ServerHost();
                host.setServerId(server.getId());
            }
            host.setHost(normalizedHost.host());
            host.setIsPrimary(i == 0 ? 1 : 0);
            host.setEnabled(normalizedHost.enabled());
            host.setSort(i);
            host.setDomainId(normalizedHost.domainId());
            host.setRemark(normalizedHost.remark());
            if (isNewHost) {
                serverHostRepository.persist(host);
            }
            if (host.getId() != null) {
                retainedIds.add(host.getId());
            }
            persistedHosts.add(host);
        }

        List<Long> removedHostIds = existingHosts.stream()
                .map(ServerHost::getId)
                .filter(Objects::nonNull)
                .filter(id -> !retainedIds.contains(id))
                .toList();
        if (!removedHostIds.isEmpty()) {
            nodeRepository.clearAccessHostIds(removedHostIds);
            serverHostRepository.deleteByIds(removedHostIds);
        }

        server.setHost(persistedHosts.isEmpty() ? null : persistedHosts.getFirst().getHost());
        return persistedHosts;
    }

    @Transactional
    public void deleteHostsByServerId(Long serverId) {
        if (serverId == null) {
            return;
        }
        List<Long> hostIds = serverHostRepository.findByServerId(serverId).stream()
                .map(ServerHost::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!hostIds.isEmpty()) {
            nodeRepository.clearAccessHostIds(hostIds);
        }
        serverHostRepository.deleteByServerId(serverId);
    }

    public List<String> getResolvedHosts(Server server) {
        return toDtos(getHostsByServerId(server != null ? server.getId() : null), server).stream()
                .map(ServerHostDto::getHost)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .toList();
    }

    private List<NormalizedHost> normalizeHosts(List<ServerHostDto> hostDtos, String fallbackHost) {
        LinkedHashMap<String, NormalizedHost> deduplicated = new LinkedHashMap<>();

        if (hostDtos != null) {
            for (ServerHostDto dto : hostDtos) {
                if (dto == null || dto.getHost() == null || dto.getHost().isBlank()) {
                    continue;
                }
                String normalized = dto.getHost().trim();
                deduplicated.putIfAbsent(normalized.toLowerCase(), new NormalizedHost(
                        dto.getId(),
                        normalized,
                        dto.getEnabled() == null ? 1 : (dto.getEnabled() == 0 ? 0 : 1),
                        dto.getDomainId(),
                        dto.getRemark()
                ));
            }
        }

        if (deduplicated.isEmpty() && fallbackHost != null && !fallbackHost.isBlank()) {
            String normalized = fallbackHost.trim();
            deduplicated.put(normalized.toLowerCase(), new NormalizedHost(null, normalized, 1, null, null));
        }

        return new ArrayList<>(new LinkedHashSet<>(deduplicated.values()));
    }

    private ServerHost resolveExistingHost(Map<Long, ServerHost> existingById,
                                           Map<String, ServerHost> existingByHost,
                                           NormalizedHost normalizedHost) {
        if (normalizedHost.id() != null) {
            ServerHost byId = existingById.remove(normalizedHost.id());
            if (byId != null) {
                existingByHost.remove(byId.getHost().trim().toLowerCase());
                return byId;
            }
        }

        return existingByHost.remove(normalizedHost.host().trim().toLowerCase());
    }

    private record NormalizedHost(Long id, String host, Integer enabled, Long domainId, String remark) {}
}

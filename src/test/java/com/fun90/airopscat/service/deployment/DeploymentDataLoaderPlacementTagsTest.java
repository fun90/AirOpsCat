package com.fun90.airopscat.service.deployment;

import com.fun90.airopscat.model.dto.deployment.NodeClient;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.repository.TagRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentDataLoaderPlacementTagsTest {
    @Test
    void sameLogicalNodeDeploysDifferentAccountsToDifferentServers() {
        TagRepository tags = new TagRepository() {
            @Override
            public Map<Long, List<Long>> findTagIdsByPlacementIds(List<Long> ids) {
                return Map.of(11L, List.of(1L), 22L, List.of(2L));
            }

            @Override
            public List<Account> findActiveAccountsByTagIds(List<Long> ids, LocalDateTime now) {
                return ids.stream().map(id -> {
                    Account account = new Account();
                    account.setId(id);
                    account.setUuid("uuid-" + id);
                    account.setAccountNo("account-" + id);
                    return account;
                }).toList();
            }

            @Override
            public Map<Long, List<Long>> findAccountIdsByTagIds(List<Long> ids) {
                return Map.of(1L, List.of(1L), 2L, List.of(2L));
            }
        };
        DeploymentDataLoader loader = new DeploymentDataLoader(null, null, tags, null, null);
        Node node = new Node();
        node.setId(7L);
        node.setCoreType("sing-box");
        node.setInbound("{}");
        NodeServer first = new NodeServer();
        first.setId(11L);
        NodeServer second = new NodeServer();
        second.setId(22L);
        NodeServer empty = new NodeServer();
        empty.setId(33L);

        assertEquals(List.of("account-1"), loader.buildNodeClientsMap(List.of(node), Map.of(7L, first))
                .get(7L).stream().map(NodeClient::email).toList());
        assertEquals(List.of("account-2"), loader.buildNodeClientsMap(List.of(node), Map.of(7L, second))
                .get(7L).stream().map(NodeClient::email).toList());
        assertTrue(loader.buildNodeClientsMap(List.of(node), Map.of(7L, empty)).get(7L).isEmpty());
    }
}

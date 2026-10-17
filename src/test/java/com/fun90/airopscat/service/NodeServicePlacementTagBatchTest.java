package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.AccountNodeSubscriptionDomainBinding;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.NodeServer;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.model.enums.NodeBatchTagUpdateMode;
import com.fun90.airopscat.model.enums.NodeDeploymentStatus;
import com.fun90.airopscat.repository.AccountNodeSubscriptionDomainBindingRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.NodeServerRepository;
import com.fun90.airopscat.repository.TagRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeServicePlacementTagBatchTest {
    @Test
    void appendKeepsEachPlacementExistingTags() {
        Node node = new Node();
        node.setId(7L);
        NodeServer first = placement(11L, 1L);
        NodeServer second = placement(22L, 2L);
        NodeService service = new NodeService();
        service.nodeRepository = new NodeRepository() {
            @Override
            public List<Node> findByIdIn(List<Long> ids) {
                return List.of(node);
            }
        };
        service.nodeServerRepository = new NodeServerRepository() {
            @Override
            public List<NodeServer> findByNodeId(Long nodeId) {
                return List.of(first, second);
            }
        };
        service.tagRepository = new TagRepository() {
            @Override
            public List<Tag> list(String query, Object... params) {
                return List.of(tag(3L));
            }
        };
        service.bindingRepository = new AccountNodeSubscriptionDomainBindingRepository() {
            @Override
            public List<AccountNodeSubscriptionDomainBinding> findByNodeId(Long nodeId) {
                return List.of();
            }
        };

        Map<String, Integer> result = service.batchUpdateNodeTags(List.of(7L), List.of(3L), NodeBatchTagUpdateMode.APPEND);

        assertEquals(1, result.get("updatedCount"));
        assertEquals(List.of(1L, 3L), first.getTags().stream().map(Tag::getId).sorted().toList());
        assertEquals(List.of(2L, 3L), second.getTags().stream().map(Tag::getId).sorted().toList());
        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY.getValue(), first.getDeployed());
        assertEquals(NodeDeploymentStatus.PENDING_DEPLOY.getValue(), second.getDeployed());
    }

    private static NodeServer placement(Long id, Long tagId) {
        NodeServer placement = new NodeServer();
        placement.setId(id);
        placement.setEnabled(1);
        placement.setDeployed(NodeDeploymentStatus.DEPLOYED.getValue());
        placement.setTags(new HashSet<>(List.of(tag(tagId))));
        return placement;
    }

    private static Tag tag(Long id) {
        Tag tag = new Tag();
        tag.setId(id);
        return tag;
    }
}

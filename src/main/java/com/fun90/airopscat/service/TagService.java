package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.TagDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.repository.NodeRepository;
import com.fun90.airopscat.repository.TagRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.util.*;

@ApplicationScoped
public class TagService {

    private final TagRepository tagRepository;
    private final NodeRepository nodeRepository;
    private final AccountRepository accountRepository;

    @Inject
    public TagService(TagRepository tagRepository, NodeRepository nodeRepository, AccountRepository accountRepository) {
        this.tagRepository = tagRepository;
        this.nodeRepository = nodeRepository;
        this.accountRepository = accountRepository;
    }

    public io.quarkus.hibernate.orm.panache.PanacheQuery<Tag> getTagPage(String search, Integer disabled) {
        // Create sort by createTime descending
        Sort sort = Sort.by("createTime").descending();
        
        // Build query string
        Map<String, Object> params = new HashMap<>();
        
        List<String> conditions = new ArrayList<>();
        
        // Search condition
        if (search != null && !search.trim().isEmpty()) {
            conditions.add("(lower(name) like :search or lower(description) like :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
        
        // Disabled filter
        if (disabled != null) {
            conditions.add("disabled = :disabled");
            params.put("disabled", disabled);
        }
        
        String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
        
        if (query.isEmpty()) {
            return tagRepository.findAll(sort);
        } else {
            return tagRepository.find(query, sort, params);
        }
    }

    public Tag getTagById(Long id) {
        return tagRepository.findById(id);
    }

    public List<Tag> getAllTags() {
        return tagRepository.findAll(Sort.by("name")).list();
    }

    public List<Tag> getEnabledTags() {
        return tagRepository.findByDisabled(0);
    }

    public TagDto convertToDto(Tag tag) {
        TagDto dto = new TagDto();
        
        // 手动设置属性，避免触发@Transient方法
        dto.setId(tag.getId());
        dto.setName(tag.getName());
        dto.setDescription(tag.getDescription());
        dto.setColor(tag.getColor());
        dto.setDisabled(tag.getDisabled());
        dto.setCreateTime(tag.getCreateTime());
        dto.setUpdateTime(tag.getUpdateTime());
        
        // 使用专门的查询来获取统计信息，避免触发懒加载
        dto.setNodeCount(tagRepository.countNodesByTagId(tag.getId()));
        dto.setAccountCount(tagRepository.countAccountsByTagId(tag.getId()));
        
        return dto;
    }

    @Transactional
    public Tag saveTag(Tag tag) {
        if (tag.getDisabled() == null) {
            tag.setDisabled(0);
        }
        tagRepository.persist(tag);
        return tag;
    }

    @Transactional
    public Tag updateTag(Tag tag) {
        Tag existingTag = tagRepository.findById(tag.getId());
        if (existingTag == null) {
            throw new EntityNotFoundException("Tag not found");
        }

        // Update basic properties
        existingTag.setName(tag.getName());
        existingTag.setDescription(tag.getDescription());
        existingTag.setColor(tag.getColor());
        existingTag.setDisabled(tag.getDisabled());

        // No need to call save/persist for updates in Panache
        return existingTag;
    }

    @Transactional
    public void deleteTag(Long id) {
        Tag tag = tagRepository.findById(id);
        if (tag == null) {
            throw new EntityNotFoundException("Tag not found");
        }
        
        // Remove associations using direct repository operations to avoid lazy loading
        // The associations will be automatically removed by cascade operations
        tagRepository.deleteById(id);
    }

    @Transactional
    public Tag toggleTagStatus(Long id, boolean disabled) {
        Tag tag = tagRepository.findById(id);
        if (tag != null) {
            tag.setDisabled(disabled ? 1 : 0);
            // No need to call save/persist for updates in Panache
            return tag;
        }
        return null;
    }

    // 标签关联管理方法
    @Transactional
    public void addTagToNode(Long nodeId, Long tagId) {
        // 验证节点和标签是否存在
        if (nodeRepository.findById(nodeId) == null) {
            throw new EntityNotFoundException("Node not found");
        }
        if (tagRepository.findById(tagId) == null) {
            throw new EntityNotFoundException("Tag not found");
        }

        // 检查关联是否已经存在，避免重复插入
        List<Tag> existingTags = tagRepository.findByNodeId(nodeId);
        boolean alreadyExists = existingTags.stream().anyMatch(tag -> tag.getId().equals(tagId));
        
        if (!alreadyExists) {
            tagRepository.insertNodeTag(nodeId, tagId);
        }
    }

    @Transactional
    public void removeTagFromNode(Long nodeId, Long tagId) {
        // 验证节点和标签是否存在
        if (nodeRepository.findById(nodeId) == null) {
            throw new EntityNotFoundException("Node not found");
        }
        if (tagRepository.findById(tagId) == null) {
            throw new EntityNotFoundException("Tag not found");
        }

        // 直接删除关联记录
        tagRepository.deleteNodeTag(nodeId, tagId);
    }

    @Transactional
    public void addTagToAccount(Long accountId, Long tagId) {
        // 验证账户和标签是否存在
        if (accountRepository.findById(accountId) == null) {
            throw new EntityNotFoundException("Account not found");
        }
        if (tagRepository.findById(tagId) == null) {
            throw new EntityNotFoundException("Tag not found");
        }

        // 检查关联是否已经存在，避免重复插入
        List<Tag> existingTags = tagRepository.findByAccountId(accountId);
        boolean alreadyExists = existingTags.stream().anyMatch(tag -> tag.getId().equals(tagId));
        
        if (!alreadyExists) {
            tagRepository.insertAccountTag(accountId, tagId);
        }
    }

    @Transactional
    public void removeTagFromAccount(Long accountId, Long tagId) {
        // 验证账户和标签是否存在
        if (accountRepository.findById(accountId) == null) {
            throw new EntityNotFoundException("Account not found");
        }
        if (tagRepository.findById(tagId) == null) {
            throw new EntityNotFoundException("Tag not found");
        }

        // 直接删除关联记录
        tagRepository.deleteAccountTag(accountId, tagId);
    }

    @Transactional
    public void updateNodeTags(Long nodeId, List<Long> tagIds) {
        // 验证节点是否存在
        if (nodeRepository.findById(nodeId) == null) {
            throw new EntityNotFoundException("Node not found");
        }

        // 删除现有的标签关联
        tagRepository.deleteAllNodeTagsByNodeId(nodeId);

        // 添加新的标签关联
        if (tagIds != null && !tagIds.isEmpty()) {
            // 验证所有标签都存在
            List<Tag> existingTags = tagRepository.list("id in ?1", tagIds);
            if (existingTags.size() != tagIds.size()) {
                throw new EntityNotFoundException("Some tags not found");
            }
            
            // 批量插入新的关联
            for (Long tagId : tagIds) {
                tagRepository.insertNodeTag(nodeId, tagId);
            }
        }
    }

    @Transactional
    public void updateAccountTags(Long accountId, List<Long> tagIds) {
        // 验证账户是否存在
        if (accountRepository.findById(accountId) == null) {
            throw new EntityNotFoundException("Account not found");
        }

        // 删除现有的标签关联
        tagRepository.deleteAllAccountTagsByAccountId(accountId);

        // 添加新的标签关联
        if (tagIds != null && !tagIds.isEmpty()) {
            validateAccountTagIds(tagIds);

            // 批量插入新的关联
            for (Long tagId : tagIds) {
                tagRepository.insertAccountTag(accountId, tagId);
            }
        }
    }

    public void validateAccountTagIds(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }

        Set<Long> uniqueTagIds = new HashSet<>(tagIds);
        List<Tag> existingTags = tagRepository.list("id in ?1", uniqueTagIds);
        if (existingTags.size() != uniqueTagIds.size()) {
            throw new EntityNotFoundException("部分标签不存在");
        }
    }

    // 查询关联关系的方法
    public List<Node> getNodesByTag(Long tagId) {
        // 使用专门的查询避免懒加载问题
        return tagRepository.findNodesByTagId(tagId);
    }

    public List<Account> getAccountsByTag(Long tagId) {
        // 使用专门的查询避免懒加载问题
        return tagRepository.findAccountsByTagId(tagId);
    }

    public List<Tag> getTagsByNode(Long nodeId) {
        return tagRepository.findByNodeId(nodeId);
    }

    public List<Tag> getTagsByAccount(Long accountId) {
        return tagRepository.findByAccountId(accountId);
    }

    // 根据标签获取匹配的Node和Account
    public List<Node> getAvailableNodesByAccount(Long accountId) {
        List<Tag> accountTags = getTagsByAccount(accountId);
        if (accountTags.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Node> availableNodes = new HashSet<>();
        for (Tag tag : accountTags) {
            availableNodes.addAll(getNodesByTag(tag.getId()));
        }

        return new ArrayList<>(availableNodes);
    }

    public List<Account> getAuthorizedAccountsByNode(Long nodeId) {
        List<Tag> nodeTags = getTagsByNode(nodeId);
        if (nodeTags.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Account> authorizedAccounts = new HashSet<>();
        for (Tag tag : nodeTags) {
            authorizedAccounts.addAll(getAccountsByTag(tag.getId()));
        }

        return new ArrayList<>(authorizedAccounts);
    }

    // 统计方法
    public Map<String, Long> getTagsStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", tagRepository.count());
        stats.put("enabled", (long) tagRepository.findByDisabled(0).size());
        stats.put("disabled", (long) tagRepository.findByDisabled(1).size());
        return stats;
    }
}

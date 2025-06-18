package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.convert.NodeConverter;
import com.fun90.airopscat.model.dto.AccountDto;
import com.fun90.airopscat.model.dto.NodeDto;
import com.fun90.airopscat.model.dto.TagDto;
import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.model.entity.Node;
import com.fun90.airopscat.model.entity.Tag;
import com.fun90.airopscat.service.AccountService;
import com.fun90.airopscat.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/tags")
public class TagController {
    
    private final TagService tagService;
    private final AccountService accountService;
    
    @Autowired
    public TagController(TagService tagService, AccountService accountService) {
        this.tagService = tagService;
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTagPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer disabled
    ) {
        Page<Tag> tagPage = tagService.getTagPage(page, size, search, disabled);
        
        // Convert to DTOs
        List<TagDto> tagDtos = tagPage.getContent().stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", tagDtos);
        response.put("total", tagPage.getTotalElements());
        response.put("pages", tagPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", tagService.getTagsStats());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TagDto> getTagById(@PathVariable Long id) {
        Tag tag = tagService.getTagById(id);
        if (tag != null) {
            TagDto dto = tagService.convertToDto(tag);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/all")
    public ResponseEntity<List<TagDto>> getAllTags() {
        List<Tag> tags = tagService.getAllTags();
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return ResponseEntity.ok(tagDtos);
    }
    
    @GetMapping("/enabled")
    public ResponseEntity<List<TagDto>> getEnabledTags() {
        List<Tag> tags = tagService.getEnabledTags();
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return ResponseEntity.ok(tagDtos);
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getTagsStats() {
        return ResponseEntity.ok(tagService.getTagsStats());
    }

    @PostMapping
    public ResponseEntity<Tag> createTag(@RequestBody Tag tag) {
        Tag savedTag = tagService.saveTag(tag);
        return ResponseEntity.ok(savedTag);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TagDto> updateTag(@PathVariable Long id, @RequestBody Tag tag) {
        Tag existingTag = tagService.getTagById(id);
        if (existingTag == null) {
            return ResponseEntity.notFound().build();
        }

        tag.setId(id);
        Tag updatedTag = tagService.updateTag(tag);
        return ResponseEntity.ok(tagService.convertToDto(updatedTag));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable Long id) {
        Tag existingTag = tagService.getTagById(id);
        if (existingTag == null) {
            return ResponseEntity.notFound().build();
        }

        tagService.deleteTag(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<Map<String, Object>> enableTag(@PathVariable Long id) {
        Tag tag = tagService.toggleTagStatus(id, false);
        if (tag != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Map<String, Object>> disableTag(@PathVariable Long id) {
        Tag tag = tagService.toggleTagStatus(id, true);
        if (tag != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
    
    // 标签关联管理接口
    @PostMapping("/{tagId}/nodes/{nodeId}")
    public ResponseEntity<Void> addTagToNode(@PathVariable Long tagId, @PathVariable Long nodeId) {
        tagService.addTagToNode(nodeId, tagId);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{tagId}/nodes/{nodeId}")
    public ResponseEntity<Void> removeTagFromNode(@PathVariable Long tagId, @PathVariable Long nodeId) {
        tagService.removeTagFromNode(nodeId, tagId);
        return ResponseEntity.ok().build();
    }
    
    @PostMapping("/{tagId}/accounts/{accountId}")
    public ResponseEntity<Void> addTagToAccount(@PathVariable Long tagId, @PathVariable Long accountId) {
        tagService.addTagToAccount(accountId, tagId);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{tagId}/accounts/{accountId}")
    public ResponseEntity<Void> removeTagFromAccount(@PathVariable Long tagId, @PathVariable Long accountId) {
        tagService.removeTagFromAccount(accountId, tagId);
        return ResponseEntity.ok().build();
    }
    
    // 批量更新标签关联
    @PutMapping("/nodes/{nodeId}/tags")
    public ResponseEntity<Void> updateNodeTags(@PathVariable Long nodeId, @RequestBody List<Long> tagIds) {
        tagService.updateNodeTags(nodeId, tagIds);
        return ResponseEntity.ok().build();
    }
    
    @PutMapping("/accounts/{accountId}/tags")
    public ResponseEntity<Void> updateAccountTags(@PathVariable Long accountId, @RequestBody List<Long> tagIds) {
        tagService.updateAccountTags(accountId, tagIds);
        return ResponseEntity.ok().build();
    }
    
    // 查询关联关系
    @GetMapping("/{tagId}/nodes")
    public ResponseEntity<List<NodeDto>> getNodesByTag(@PathVariable Long tagId) {
        List<Node> nodes = tagService.getNodesByTag(tagId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(nodeDtos);
    }
    
    @GetMapping("/{tagId}/accounts")
    public ResponseEntity<List<AccountDto>> getAccountsByTag(@PathVariable Long tagId) {
        List<Account> accounts = tagService.getAccountsByTag(tagId);
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());
        return ResponseEntity.ok(accountDtos);
    }
    
    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<List<TagDto>> getTagsByNode(@PathVariable Long nodeId) {
        List<Tag> tags = tagService.getTagsByNode(nodeId);
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return ResponseEntity.ok(tagDtos);
    }
    
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<List<TagDto>> getTagsByAccount(@PathVariable Long accountId) {
        List<Tag> tags = tagService.getTagsByAccount(accountId);
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return ResponseEntity.ok(tagDtos);
    }
    
    // 根据标签获取匹配关系
    @GetMapping("/accounts/{accountId}/available-nodes")
    public ResponseEntity<List<NodeDto>> getAvailableNodesByAccount(@PathVariable Long accountId) {
        List<Node> nodes = tagService.getAvailableNodesByAccount(accountId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(nodeDtos);
    }
    
    @GetMapping("/nodes/{nodeId}/authorized-accounts")
    public ResponseEntity<List<AccountDto>> getAuthorizedAccountsByNode(@PathVariable Long nodeId) {
        List<Account> accounts = tagService.getAuthorizedAccountsByNode(nodeId);
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());
        return ResponseEntity.ok(accountDtos);
    }
} 
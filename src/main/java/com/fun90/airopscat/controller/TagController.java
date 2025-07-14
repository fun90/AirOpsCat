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
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Path("/api/admin/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TagController {
    
    @Inject
    TagService tagService;
    
    @Inject
    AccountService accountService;

    @GET
    public Response getTagPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("disabled") Integer disabled
    ) {
        PanacheQuery<Tag> tagQuery = tagService.getTagPage(search, disabled);
        tagQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<TagDto> tagDtos = tagQuery.list().stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", tagDtos);
        response.put("total", tagQuery.count());
        response.put("pages", tagQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", tagService.getTagsStats());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getTagById(@PathParam("id") Long id) {
        Tag tag = tagService.getTagById(id);
        if (tag != null) {
            TagDto dto = tagService.convertToDto(tag);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/all")
    public Response getAllTags() {
        List<Tag> tags = tagService.getAllTags();
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return Response.ok(tagDtos).build();
    }
    
    @GET
    @Path("/enabled")
    public Response getEnabledTags() {
        List<Tag> tags = tagService.getEnabledTags();
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return Response.ok(tagDtos).build();
    }
    
    @GET
    @Path("/stats")
    public Response getTagsStats() {
        return Response.ok(tagService.getTagsStats()).build();
    }

    @POST
    public Response createTag(Tag tag) {
        Tag savedTag = tagService.saveTag(tag);
        return Response.ok(savedTag).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateTag(@PathParam("id") Long id, Tag tag) {
        Tag existingTag = tagService.getTagById(id);
        if (existingTag == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        tag.setId(id);
        Tag updatedTag = tagService.updateTag(tag);
        return Response.ok(tagService.convertToDto(updatedTag)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteTag(@PathParam("id") Long id) {
        Tag existingTag = tagService.getTagById(id);
        if (existingTag == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        tagService.deleteTag(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableTag(@PathParam("id") Long id) {
        Tag tag = tagService.toggleTagStatus(id, false);
        if (tag != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableTag(@PathParam("id") Long id) {
        Tag tag = tagService.toggleTagStatus(id, true);
        if (tag != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    // 标签关联管理接口
    @POST
    @Path("/{tagId}/nodes/{nodeId}")
    public Response addTagToNode(@PathParam("tagId") Long tagId, @PathParam("nodeId") Long nodeId) {
        tagService.addTagToNode(nodeId, tagId);
        return Response.ok().build();
    }
    
    @DELETE
    @Path("/{tagId}/nodes/{nodeId}")
    public Response removeTagFromNode(@PathParam("tagId") Long tagId, @PathParam("nodeId") Long nodeId) {
        tagService.removeTagFromNode(nodeId, tagId);
        return Response.ok().build();
    }
    
    @POST
    @Path("/{tagId}/accounts/{accountId}")
    public Response addTagToAccount(@PathParam("tagId") Long tagId, @PathParam("accountId") Long accountId) {
        tagService.addTagToAccount(accountId, tagId);
        return Response.ok().build();
    }
    
    @DELETE
    @Path("/{tagId}/accounts/{accountId}")
    public Response removeTagFromAccount(@PathParam("tagId") Long tagId, @PathParam("accountId") Long accountId) {
        tagService.removeTagFromAccount(accountId, tagId);
        return Response.ok().build();
    }
    
    // 批量更新标签关联
    @PUT
    @Path("/nodes/{nodeId}/tags")
    public Response updateNodeTags(@PathParam("nodeId") Long nodeId, List<Long> tagIds) {
        tagService.updateNodeTags(nodeId, tagIds);
        return Response.ok().build();
    }
    
    @PUT
    @Path("/accounts/{accountId}/tags")
    public Response updateAccountTags(@PathParam("accountId") Long accountId, List<Long> tagIds) {
        tagService.updateAccountTags(accountId, tagIds);
        return Response.ok().build();
    }
    
    // 查询关联关系
    @GET
    @Path("/{tagId}/nodes")
    public Response getNodesByTag(@PathParam("tagId") Long tagId) {
        List<Node> nodes = tagService.getNodesByTag(tagId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return Response.ok(nodeDtos).build();
    }
    
    @GET
    @Path("/{tagId}/accounts")
    public Response getAccountsByTag(@PathParam("tagId") Long tagId) {
        List<Account> accounts = tagService.getAccountsByTag(tagId);
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());
        return Response.ok(accountDtos).build();
    }
    
    @GET
    @Path("/nodes/{nodeId}")
    public Response getTagsByNode(@PathParam("nodeId") Long nodeId) {
        List<Tag> tags = tagService.getTagsByNode(nodeId);
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return Response.ok(tagDtos).build();
    }
    
    @GET
    @Path("/accounts/{accountId}")
    public Response getTagsByAccount(@PathParam("accountId") Long accountId) {
        List<Tag> tags = tagService.getTagsByAccount(accountId);
        List<TagDto> tagDtos = tags.stream()
                .map(tag -> tagService.convertToDto(tag))
                .collect(Collectors.toList());
        return Response.ok(tagDtos).build();
    }
    
    // 根据标签获取匹配关系
    @GET
    @Path("/accounts/{accountId}/available-nodes")
    public Response getAvailableNodesByAccount(@PathParam("accountId") Long accountId) {
        List<Node> nodes = tagService.getAvailableNodesByAccount(accountId);
        List<NodeDto> nodeDtos = nodes.stream()
                .map(NodeConverter::toDto)
                .collect(Collectors.toList());
        return Response.ok(nodeDtos).build();
    }
    
    @GET
    @Path("/nodes/{nodeId}/authorized-accounts")
    public Response getAuthorizedAccountsByNode(@PathParam("nodeId") Long nodeId) {
        List<Account> accounts = tagService.getAuthorizedAccountsByNode(nodeId);
        List<AccountDto> accountDtos = accounts.stream()
                .map(account -> accountService.convertToDto(account))
                .collect(Collectors.toList());
        return Response.ok(accountDtos).build();
    }
} 
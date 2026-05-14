package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingDto;
import com.fun90.airopscat.model.dto.AccountNodeSubscriptionDomainBindingRequest;
import com.fun90.airopscat.service.AccountNodeSubscriptionDomainBindingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/account-node-subscription-domain-bindings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountNodeSubscriptionDomainBindingController {

    @Inject
    AccountNodeSubscriptionDomainBindingService bindingService;

    @GET
    @Path("/accounts/{accountId}")
    public Response listByAccount(@PathParam("accountId") Long accountId) {
        try {
            List<AccountNodeSubscriptionDomainBindingDto> bindings = bindingService.listByAccount(accountId);
            return Response.ok(bindings).build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    @GET
    @Path("/accounts/{accountId}/available-nodes")
    public Response searchAvailableNodes(@PathParam("accountId") Long accountId,
                                         @QueryParam("search") String search,
                                         @QueryParam("size") @DefaultValue("20") int size) {
        try {
            return Response.ok(bindingService.searchAvailableNodes(accountId, search, size)).build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    @GET
    @Path("/nodes/{nodeId}")
    public Response listByNode(@PathParam("nodeId") Long nodeId) {
        try {
            List<AccountNodeSubscriptionDomainBindingDto> bindings = bindingService.listByNode(nodeId);
            return Response.ok(bindings).build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    @POST
    public Response save(AccountNodeSubscriptionDomainBindingRequest request) {
        try {
            return Response.ok(bindingService.save(request)).build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e);
        }
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enable(@PathParam("id") Long id) {
        try {
            return Response.ok(bindingService.setEnabled(id, true)).build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disable(@PathParam("id") Long id) {
        try {
            return Response.ok(bindingService.setEnabled(id, false)).build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        try {
            bindingService.delete(id);
            return Response.ok().build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    @GET
    public Response list(@QueryParam("accountId") Long accountId, @QueryParam("nodeId") Long nodeId) {
        try {
            if (accountId != null) {
                return listByAccount(accountId);
            }
            if (nodeId != null) {
                return listByNode(nodeId);
            }
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "必须指定账户或节点"))
                    .build();
        } catch (EntityNotFoundException e) {
            return notFound(e);
        }
    }

    private Response notFound(Exception e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("message", e.getMessage()))
                .build();
    }

    private Response badRequest(Exception e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("message", e.getMessage()))
                .build();
    }
}

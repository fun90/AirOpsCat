package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.entity.User;
import com.fun90.airopscat.service.UserService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserController {
    
    @Inject
    UserService userService;

    @GET
    public Response getUserPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("role") String role,
            @QueryParam("status") String status
    ) {
        PanacheQuery<User> userQuery = userService.getUserPage(search, role, status);
        userQuery.page(Page.of(page - 1, size));

        Map<String, Object> response = new HashMap<>();
        response.put("records", userQuery.list());
        response.put("total", userQuery.count());
        response.put("pages", userQuery.pageCount());
        response.put("current", page);
        response.put("size", size);

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getUserById(@PathParam("id") Long id) {
        User user = userService.getUserById(id);
        if (user != null) {
            return Response.ok(user).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @POST
    public Response createUser(User user) {
        User savedUser = userService.saveUser(user);
        return Response.ok(savedUser).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateUser(@PathParam("id") Long id, User user) {
        User existingUser = userService.getUserById(id);
        if (existingUser == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        user.setId(id);
        User updatedUser = userService.updateUser(user);
        return Response.ok(updatedUser).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteUser(@PathParam("id") Long id) {
        User existingUser = userService.getUserById(id);
        if (existingUser == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        userService.deleteUser(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableUser(@PathParam("id") Long id) {
        User user = userService.toggleUserStatus(id, false);
        if (user != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableUser(@PathParam("id") Long id) {
        User user = userService.toggleUserStatus(id, true);
        if (user != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.TransactionDto;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.enums.PaymentMethod;
import com.fun90.airopscat.model.enums.TransactionType;
import com.fun90.airopscat.service.TransactionService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
@Path("/api/admin/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionController {
    
    @Inject
    TransactionService transactionService;

    @GET
    public Response getTransactionPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("type") Integer type,
            @QueryParam("businessTable") String businessTable,
            @QueryParam("businessId") Long businessId,
            @QueryParam("startDate") String startDateStr,
            @QueryParam("endDate") String endDateStr
    ) {
        LocalDateTime startDate = startDateStr != null ? LocalDateTime.parse(startDateStr) : null;
        LocalDateTime endDate = endDateStr != null ? LocalDateTime.parse(endDateStr) : null;
        
        PanacheQuery<Transaction> transactionQuery = transactionService.getTransactionPage(
                search, type, businessTable, businessId, startDate, endDate);
        transactionQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs
        List<TransactionDto> transactionDtos = transactionQuery.list().stream()
                .map(transaction -> transactionService.convertToDto(transaction))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", transactionDtos);
        response.put("total", transactionQuery.count());
        response.put("pages", transactionQuery.pageCount());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", transactionService.getTransactionStats());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getTransactionById(@PathParam("id") Long id) {
        Transaction transaction = transactionService.getTransactionById(id);
        if (transaction != null) {
            TransactionDto dto = transactionService.convertToDto(transaction);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/types")
    public Response getTransactionTypes() {
        List<Map<String, String>> types = Stream.of(TransactionType.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", String.valueOf(type.getValue()));
                    map.put("label", type.getDescription());
                    return map;
                })
                .collect(Collectors.toList());
        
        return Response.ok(types).build();
    }

    @GET
    @Path("/paymentMethods")
    public Response getPaymentMethods() {
        List<Map<String, String>> types = Stream.of(PaymentMethod.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", type.getValue());
                    map.put("label", type.getDescription());
                    return map;
                })
                .collect(Collectors.toList());

        return Response.ok(types).build();
    }
    
    @GET
    @Path("/business-tables")
    public Response getBusinessTables() {
        List<Map<String, String>> tables = List.of(
                createMapEntry("account", "账户"),
                createMapEntry("domain", "域名"),
                createMapEntry("server", "服务器")
        );
        
        return Response.ok(tables).build();
    }
    
    private Map<String, String> createMapEntry(String value, String label) {
        Map<String, String> map = new HashMap<>();
        map.put("value", value);
        map.put("label", label);
        return map;
    }
    
    @GET
    @Path("/stats")
    public Response getTransactionStats() {
        return Response.ok(transactionService.getTransactionStats()).build();
    }
    
    @GET
    @Path("/monthly-stats")
    public Response getMonthlyStats(
            @QueryParam("months") @DefaultValue("6") int months
    ) {
        return Response.ok(transactionService.getMonthlyStats(months)).build();
    }
    
    @GET
    @Path("/business/{businessTable}/{businessId}")
    public Response getTransactionsByBusiness(
            @PathParam("businessTable") String businessTable,
            @PathParam("businessId") Long businessId
    ) {
        List<Transaction> transactions = transactionService.getByBusinessTableAndId(businessTable, businessId);
        List<TransactionDto> dtos = transactions.stream()
                .map(transaction -> transactionService.convertToDto(transaction))
                .collect(Collectors.toList());
        
        return Response.ok(dtos).build();
    }

    @POST
    public Response createTransaction(Transaction transaction) {
        Transaction savedTransaction = transactionService.saveTransaction(transaction);
        return Response.ok(savedTransaction).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateTransaction(@PathParam("id") Long id, Transaction transaction) {
        Transaction existingTransaction = transactionService.getTransactionById(id);
        if (existingTransaction == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        transaction.setId(id);
        Transaction updatedTransaction = transactionService.updateTransaction(transaction);
        return Response.ok(updatedTransaction).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteTransaction(@PathParam("id") Long id) {
        Transaction existingTransaction = transactionService.getTransactionById(id);
        if (existingTransaction == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        transactionService.deleteTransaction(id);
        return Response.ok().build();
    }
}
package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.DomainDto;
import com.fun90.airopscat.model.dto.DomainDnsProviderBindingDto;
import com.fun90.airopscat.model.dto.DomainDnsProviderBindingRequest;
import com.fun90.airopscat.model.dto.DomainDnsPullResponse;
import com.fun90.airopscat.model.dto.DomainDnsPushResponse;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.enums.PaymentMethod;
import com.fun90.airopscat.model.enums.TransactionType;
import com.fun90.airopscat.service.DomainService;
import com.fun90.airopscat.service.DomainDnsBindingService;
import com.fun90.airopscat.service.DomainDnsPullService;
import com.fun90.airopscat.service.DomainDnsPushService;
import com.fun90.airopscat.service.TransactionService;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
@Path("/api/admin/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DomainController {
    
    @Inject
    DomainService domainService;

    @Inject
    TransactionService transactionService;

    @Inject
    DomainDnsBindingService domainDnsBindingService;

    @Inject
    DomainDnsPullService domainDnsPullService;

    @Inject
    DomainDnsPushService domainDnsPushService;

    @GET
    public Response getDomainPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("expiryFrom") String expiryFromStr,
            @QueryParam("expiryTo") String expiryToStr
    ) {
        LocalDate expiryFrom = expiryFromStr != null ? LocalDate.parse(expiryFromStr) : null;
        LocalDate expiryTo = expiryToStr != null ? LocalDate.parse(expiryToStr) : null;
        
        PanacheQuery<Domain> domainQuery = domainService.getDomainPage(search, expiryFrom, expiryTo);
        domainQuery.page(Page.of(page - 1, size));
        
        // Convert to DTOs and add expiration info
        List<DomainDto> domainDtos = domainQuery.list().stream()
                .map(domain -> domainService.convertToDto(domain))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", domainDtos);
        response.put("total", domainQuery.count());
        response.put("pages", domainQuery.pageCount());
        response.put("current", page);
        response.put("size", size);

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getDomainById(@PathParam("id") Long id) {
        Domain domain = domainService.getDomainById(id);
        if (domain != null) {
            return Response.ok(domainService.convertToDto(domain)).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    
    @GET
    @Path("/expiring")
    public Response getExpiringDomains(
            @QueryParam("days") @DefaultValue("30") int days
    ) {
        List<Domain> domains = domainService.getExpiringDomains(days);
        List<DomainDto> domainDtos = domains.stream()
                .map(domain -> domainService.convertToDto(domain))
                .collect(Collectors.toList());
        return Response.ok(domainDtos).build();
    }
    
    @GET
    @Path("/stats")
    public Response getDomainStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", domainService.getDomainPage(null, null, null).count());
        stats.put("expiredCount", domainService.countExpiredDomains());
        stats.put("expiringCount", domainService.countExpiringInOneMonth());
        stats.put("totalCost", domainService.getTotalDomainCost());
        return Response.ok(stats).build();
    }

    @POST
    public Response createDomain(Domain domain) {
        Domain savedDomain = domainService.saveDomain(domain);
        return Response.ok(savedDomain).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateDomain(@PathParam("id") Long id, Domain domain) {
        Domain existingDomain = domainService.getDomainById(id);
        if (existingDomain == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        domain.setId(id);
        Domain updatedDomain = domainService.updateDomain(domain);
        return Response.ok(domainService.convertToDto(updatedDomain)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteDomain(@PathParam("id") Long id) {
        Domain existingDomain = domainService.getDomainById(id);
        if (existingDomain == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        domainService.deleteDomain(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/renew")
    public Response renewDomain(
            @PathParam("id") Long id,
            @QueryParam("expiryDate") String expiryDateStr,
            @QueryParam("amount") BigDecimal amount,
            @QueryParam("paymentMethod") String paymentMethod
    ) {
        LocalDate expiryDate = LocalDate.parse(expiryDateStr);
        Domain existingDomain = domainService.getDomainById(id);
        LocalDate baseDate = existingDomain != null ? existingDomain.getExpireDate() : null;
        LocalDate today = LocalDate.now();
        if (baseDate == null || baseDate.isBefore(today)) {
            baseDate = today;
        }

        Domain domain = domainService.renewDomain(id, expiryDate);

        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            if (paymentMethod == null || paymentMethod.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("message", "填写金额时必须选择付款方式"))
                        .build();
            }
            if (PaymentMethod.fromValue(paymentMethod) == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("message", "付款方式不合法"))
                        .build();
            }

            YearMonth baseMonth = YearMonth.from(baseDate);
            YearMonth newMonth = YearMonth.from(expiryDate);
            int months = (newMonth.getYear() - baseMonth.getYear()) * 12
                    + (newMonth.getMonthValue() - baseMonth.getMonthValue());
            if (months < 1) {
                months = 1;
            }

            Transaction transaction = new Transaction();
            transaction.setType(TransactionType.EXPENSE.getValue());
            transaction.setAmount(amount);
            transaction.setBusinessTable("domain");
            transaction.setBusinessId(domain.getId());
            transaction.setDescription("域名：" + months + "月");
            transaction.setRemark(domain.getRemark());
            transaction.setPaymentMethod(paymentMethod);
            transactionService.saveTransaction(transaction);
        }

        return Response.ok(domainService.convertToDto(domain)).build();
    }

    @GET
    @Path("/{id}/dns-provider")
    public Response getDnsProviderBinding(@PathParam("id") Long id) {
        try {
            DomainDnsProviderBindingDto dto = domainDnsBindingService.getBinding(id);
            return Response.ok(dto).build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}/dns-provider")
    public Response bindDnsProvider(@PathParam("id") Long id, DomainDnsProviderBindingRequest request) {
        try {
            DomainDnsProviderBindingDto dto = domainDnsBindingService.bind(id, request);
            return Response.ok(dto).build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}/dns-provider")
    public Response unbindDnsProvider(@PathParam("id") Long id) {
        try {
            domainDnsBindingService.unbind(id);
            return Response.ok().build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/dns-records/pull")
    public Response pullDnsRecords(@PathParam("id") Long id) {
        try {
            DomainDnsPullResponse response = domainDnsPullService.pull(id);
            return Response.ok(response).build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/dns-records/push")
    public Response pushDnsRecords(@PathParam("id") Long id) {
        try {
            DomainDnsPushResponse response = domainDnsPushService.push(id);
            return Response.ok(response).build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", e.getMessage())).build();
        }
    }
}

package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.ServerDto;
import com.fun90.airopscat.model.dto.ServerTrafficCalibrationDto;
import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.model.entity.ServerTrafficStats;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.enums.PaymentMethod;
import com.fun90.airopscat.model.enums.TransactionType;
import com.fun90.airopscat.service.ServerService;
import com.fun90.airopscat.service.ServerTrafficStatsService;
import com.fun90.airopscat.service.TransactionService;
import com.fun90.airopscat.service.deployment.NodeDeploymentService;
import com.fun90.airopscat.service.NodeService;
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
@Path("/api/admin/servers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerController {

    @Inject
    ServerService serverService;

    @Inject
    ServerTrafficStatsService serverTrafficStatsService;

    @Inject
    TransactionService transactionService;

    @Inject
    NodeDeploymentService nodeDeploymentService;

    @Inject
    NodeService nodeService;

    @Inject
    com.fun90.airopscat.service.AccountOnlineIpService accountOnlineIpService;

    @GET
    public Response getServerPage(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("search") String search,
            @QueryParam("supplier") String supplier,
            @QueryParam("expired") Boolean expired,
            @QueryParam("disabled") Boolean disabled
    ) {
        PanacheQuery<Server> serverQuery = serverService.getServerPage(search, supplier, expired, disabled);
        serverQuery.page(Page.of(page - 1, size));

        // Convert to DTOs
        List<ServerDto> serverDtos = serverQuery.list().stream()
                .map(server -> serverService.convertToDto(server))
                .collect(Collectors.toList());
        serverTrafficStatsService.fillCurrentPeriodTraffic(serverDtos);

        // Fill online account count (batch query to avoid N+1)
        Map<String, Long> onlineCountByIp = accountOnlineIpService.getAllOnlineRecords().stream()
                .collect(Collectors.groupingBy(
                        com.fun90.airopscat.model.dto.AccountOnlineIpDto::getNodeIp,
                        Collectors.counting()
                ));
        serverDtos.forEach(dto -> dto.setOnlineAccountCount(onlineCountByIp.getOrDefault(dto.getIp(), 0L).intValue()));

        Map<String, Object> response = new HashMap<>();
        response.put("records", serverDtos);
        response.put("total", serverQuery.count());
        response.put("pages", serverQuery.pageCount());
        response.put("current", page);
        response.put("size", size);

        // Add statistics
        response.put("stats", serverService.getServersStats());
        response.put("supplierStats", serverService.getServersBySupplier());
        response.put("totalCost", serverService.getTotalServerCost());

        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getServerById(@PathParam("id") Long id) {
        Server server = serverService.getServerById(id);
        if (server != null) {
            ServerDto dto = serverService.convertToDto(server);
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @GET
    @Path("/{id}/config-preview")
    public Response getServerConfigPreview(@PathParam("id") Long id) {
        Server server = serverService.getServerById(id);
        if (server == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Map<String, String> configs = nodeDeploymentService.previewServerConfigs(id);
        Map<String, Object> response = new HashMap<>();
        response.put("serverId", id);
        response.put("serverName", server.getName());
        response.put("serverIp", server.getIp());
        response.put("coreTypes", configs.keySet());
        response.put("configs", configs);
        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}/online-accounts")
    public Response getServerOnlineAccounts(@PathParam("id") Long id) {
        Server server = serverService.getServerById(id);
        if (server == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(accountOnlineIpService.getOnlineAccountsByServerIp(server.getIp())).build();
    }

    @GET
    @Path("/suppliers")
    public Response getServersBySupplier() {
        return Response.ok(serverService.getServersBySupplier()).build();
    }

    @GET
    @Path("/auth-types")
    public Response getAuthTypes() {
        return Response.ok(serverService.getAuthTypeOptions()).build();
    }

    @GET
    @Path("/stats")
    public Response getServersStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.putAll(serverService.getServersStats());
        stats.put("supplierStats", serverService.getServersBySupplier());
        stats.put("totalCost", serverService.getTotalServerCost());
        return Response.ok(stats).build();
    }

    @POST
    @Path("/test-connection")
    public Response testConnection(ServerDto server) {
        boolean success = serverService.testConnection(server);

        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "连接成功" : "连接失败");

        return Response.ok(response).build();
    }

    @POST
    public Response createServer(ServerDto server) {
        Server savedServer = serverService.saveServer(server);
        return Response.ok(serverService.convertToDto(savedServer)).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateServer(@PathParam("id") Long id, ServerDto server) {
        Server existingServer = serverService.getServerById(id);
        if (existingServer == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        server.setId(id);
        Server updatedServer = serverService.updateServer(server);
        ServerDto dto = serverService.convertToDto(updatedServer);
        return Response.ok(dto).build();
    }

    @PUT
    @Path("/{id}/traffic-calibration")
    public Response calibrateTraffic(@PathParam("id") Long id, ServerTrafficCalibrationDto calibrationDto) {
        if (calibrationDto == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "请求数据不能为空"))
                    .build();
        }
        if (calibrationDto.getUploadGb() == null || calibrationDto.getUploadGb().compareTo(BigDecimal.ZERO) < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "上传流量必须大于或等于 0"))
                    .build();
        }
        if (calibrationDto.getDownloadGb() == null || calibrationDto.getDownloadGb().compareTo(BigDecimal.ZERO) < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "下载流量必须大于或等于 0"))
                    .build();
        }
        if (calibrationDto.getPeriodStartDate() == null || calibrationDto.getPeriodEndDate() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "请选择完整的流量统计周期"))
                    .build();
        }
        if (calibrationDto.getPeriodEndDate().isBefore(calibrationDto.getPeriodStartDate())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "流量统计周期结束时间不能早于开始时间"))
                    .build();
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (now.isBefore(calibrationDto.getPeriodStartDate()) || now.isAfter(calibrationDto.getPeriodEndDate())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "当前时间必须在流量统计周期内"))
                    .build();
        }

        long uploadBytes = calibrationDto.getUploadGb()
                .multiply(BigDecimal.valueOf(1024L * 1024L * 1024L))
                .longValue();
        long downloadBytes = calibrationDto.getDownloadGb()
                .multiply(BigDecimal.valueOf(1024L * 1024L * 1024L))
                .longValue();

        ServerTrafficStats stats = serverTrafficStatsService.calibrateTrafficStats(
                id,
                calibrationDto.getPeriodStartDate(),
                calibrationDto.getPeriodEndDate(),
                uploadBytes,
                downloadBytes
        );
        if (stats == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("message", "当前流量统计记录不存在"))
                    .build();
        }

        Server existingServer = serverService.getServerById(stats.getServerId());
        if (existingServer == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        ServerDto dto = serverService.convertToDto(existingServer);
        dto.setTrafficStatsId(stats.getId());
        dto.setTrafficUploadBytes(stats.getUploadBytes());
        dto.setTrafficDownloadBytes(stats.getDownloadBytes());
        dto.setTrafficTotalBytes(stats.getUploadBytes() + stats.getDownloadBytes());
        dto.setTrafficPeriodStart(stats.getPeriodStart());
        dto.setTrafficPeriodEnd(stats.getPeriodEnd());
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteServer(@PathParam("id") Long id) {
        Server existingServer = serverService.getServerById(id);
        if (existingServer == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!nodeService.getNodesByServer(id).isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "服务器下还有节点，不能删除"))
                    .build();
        }

        serverService.deleteServer(id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enableServer(@PathParam("id") Long id) {
        Server server = serverService.toggleServerStatus(id, false);
        if (server != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 0);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disableServer(@PathParam("id") Long id) {
        Server server = serverService.toggleServerStatus(id, true);
        if (server != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", id);
            response.put("disabled", 1);
            return Response.ok(response).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @PATCH
    @Path("/{id}/renew")
    public Response renewServer(
            @PathParam("id") Long id,
            @QueryParam("expiryDate") String expiryDateStr,
            @QueryParam("amount") BigDecimal amount,
            @QueryParam("paymentMethod") String paymentMethod
    ) {
        LocalDate expiryDate = LocalDate.parse(expiryDateStr);
        Server existingServer = serverService.getServerById(id);
        LocalDate baseDate = existingServer != null ? existingServer.getExpireDate() : null;
        LocalDate today = LocalDate.now();
        if (baseDate == null || baseDate.isBefore(today)) {
            baseDate = today;
        }

        Server server = serverService.renewServer(id, expiryDate);

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
            transaction.setBusinessTable("server");
            transaction.setBusinessId(server.getId());
            transaction.setDescription("服务器：" + months + "月");
            transaction.setRemark(server.getRemark());
            transaction.setPaymentMethod(paymentMethod);
            transactionService.saveTransaction(transaction);
        }

        ServerDto dto = serverService.convertToDto(server);
        return Response.ok(dto).build();
    }
}

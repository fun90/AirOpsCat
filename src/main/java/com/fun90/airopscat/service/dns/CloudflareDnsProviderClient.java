package com.fun90.airopscat.service.dns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun90.airopscat.model.dto.DnsProviderConfigRequest;
import com.fun90.airopscat.model.dto.DnsBatchChangeItem;
import com.fun90.airopscat.model.dto.DnsBatchChangeRequest;
import com.fun90.airopscat.model.dto.DnsBatchChangeResponse;
import com.fun90.airopscat.model.dto.DnsProviderRecord;
import com.fun90.airopscat.model.dto.DnsProviderTestResponse;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.enums.DnsProviderCheckStatus;
import com.fun90.airopscat.model.enums.DnsProviderType;
import com.fun90.airopscat.util.JsonUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ApplicationScoped
public class CloudflareDnsProviderClient implements DnsProviderClient {

    private static final String TOKEN_VERIFY_URL_TEMPLATE = "https://api.cloudflare.com/client/v4/user/tokens/verify";
    private static final String DNS_RECORDS_URL_TEMPLATE = "https://api.cloudflare.com/client/v4/zones/%s/dns_records";
    private static final String DNS_RECORDS_BATCH_URL_TEMPLATE = "https://api.cloudflare.com/client/v4/zones/%s/dns_records/batch";

    private final Client client = ClientBuilder.newClient();
    private final ObjectMapper objectMapper;

    @Inject
    public CloudflareDnsProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DnsProviderType getProviderType() {
        return DnsProviderType.CLOUDFLARE;
    }

    @Override
    public void validateConfigRequest(DnsProviderConfigRequest request) {
        if (request.getApiToken() == null || request.getApiToken().isBlank()) {
            throw new IllegalArgumentException("Cloudflare API Token 不能为空");
        }
        if (request.getAccountId() == null || request.getAccountId().isBlank()) {
            throw new IllegalArgumentException("Cloudflare Account ID 不能为空");
        }
    }

    @Override
    public DnsProviderTestResponse testConnection(DnsProviderConfig config) {
        DnsProviderTestResponse response = new DnsProviderTestResponse();
        response.setCheckTime(LocalDateTime.now());

        String apiToken = extractApiToken(config);
        String accountId = extractAccountId(config);
        log.info("Cloudflare token verify start, configId={}, accountId={}", config.getId(), accountId);
        if (apiToken == null || apiToken.isBlank()) {
            response.setSuccess(false);
            response.setStatus(DnsProviderCheckStatus.FAILED);
            response.setMessage("未配置 Cloudflare API Token");
            log.warn("Cloudflare token verify skipped, missing api token, configId={}, accountId={}", config.getId(), accountId);
            return response;
        }
        if (accountId == null || accountId.isBlank()) {
            response.setSuccess(false);
            response.setStatus(DnsProviderCheckStatus.FAILED);
            response.setMessage("未配置 Cloudflare Account ID");
            log.warn("Cloudflare token verify skipped, missing accountId, configId={}", config.getId());
            return response;
        }

        Response verifyResponse = null;
        try {
            verifyResponse = client.target(TOKEN_VERIFY_URL_TEMPLATE)
                    .request(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                    .get();
            log.info("Cloudflare token verify response received, configId={}, accountId={}, status={}",
                    config.getId(), accountId, verifyResponse.getStatus());

            String body = verifyResponse.readEntity(String.class);
            JsonNode jsonNode = objectMapper.readTree(body);
            boolean success = jsonNode.path("success").asBoolean(false);

            response.setSuccess(success);
            response.setStatus(success ? DnsProviderCheckStatus.SUCCESS : DnsProviderCheckStatus.FAILED);

            if (success) {
                response.setMessage("Cloudflare Token 验证通过");
            } else {
                JsonNode errors = jsonNode.path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    response.setMessage(errors.get(0).path("message").asText("Cloudflare Token 验证失败"));
                } else {
                    response.setMessage("Cloudflare Token 验证失败");
                }
            }
            log.info("Cloudflare token verify finished, configId={}, accountId={}, success={}, message={}",
                    config.getId(), accountId, response.isSuccess(), response.getMessage());
            return response;
        } catch (Exception e) {
            response.setSuccess(false);
            response.setStatus(DnsProviderCheckStatus.FAILED);
            response.setMessage("连接 Cloudflare 失败: " + e.getMessage());
            log.error("Cloudflare token verify failed, configId={}, accountId={}, message={}",
                    config.getId(), accountId, e.getMessage(), e);
            return response;
        } finally {
            if (verifyResponse != null) {
                verifyResponse.close();
            }
        }
    }

    @Override
    public List<DnsProviderRecord> listRecords(DnsProviderConfig config, Domain domain) {
        String apiToken = extractApiToken(config);
        String zoneId = extractZoneId(domain);
        String domainName = domain.getDomain();

        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("未配置 Cloudflare API Token");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("当前域名未配置 Cloudflare Zone ID");
        }

        log.info("Cloudflare dns pull start, configId={}, domainId={}, domain={}, zoneId={}",
                config.getId(), domain.getId(), domainName, zoneId);
        List<DnsProviderRecord> records = new ArrayList<>();
        int page = 1;
        int totalPages = 1;

        do {
            Response listResponse = null;
            try {
                log.info("Cloudflare dns pull request, configId={}, domainId={}, domain={}, zoneId={}, page={}, perPage=100",
                        config.getId(), domain.getId(), domainName, zoneId, page);
                listResponse = client.target(DNS_RECORDS_URL_TEMPLATE.formatted(zoneId))
                        .queryParam("page", page)
                        .queryParam("per_page", 100)
                        .request(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                        .get();
                log.info("Cloudflare dns pull response received, configId={}, domainId={}, zoneId={}, page={}, status={}",
                        config.getId(), domain.getId(), zoneId, page, listResponse.getStatus());

                String body = listResponse.readEntity(String.class);
                JsonNode root = objectMapper.readTree(body);
                if (!root.path("success").asBoolean(false)) {
                    JsonNode errors = root.path("errors");
                    String message = "Cloudflare DNS 记录拉取失败";
                    if (errors.isArray() && !errors.isEmpty()) {
                        message = errors.get(0).path("message").asText(message);
                    }
                    log.warn("Cloudflare dns pull failed response, configId={}, domainId={}, zoneId={}, page={}, message={}",
                            config.getId(), domain.getId(), zoneId, page, message);
                    throw new IllegalStateException(message);
                }

                JsonNode resultInfo = root.path("result_info");
                totalPages = Math.max(resultInfo.path("total_pages").asInt(1), 1);
                JsonNode result = root.path("result");
                int pageRecordCount = result.isArray() ? result.size() : 0;
                if (result.isArray()) {
                    for (JsonNode item : result) {
                        records.add(toProviderRecord(item));
                    }
                }
                log.info("Cloudflare dns pull page parsed, configId={}, domainId={}, zoneId={}, page={}, pageRecordCount={}, totalPages={}, accumulatedCount={}",
                        config.getId(), domain.getId(), zoneId, page, pageRecordCount, totalPages, records.size());
                page++;
            } catch (Exception e) {
                log.error("Cloudflare dns pull failed, configId={}, domainId={}, domain={}, zoneId={}, page={}, message={}",
                        config.getId(), domain.getId(), domainName, zoneId, page, e.getMessage(), e);
                throw new IllegalStateException("Cloudflare DNS 记录拉取失败: " + e.getMessage(), e);
            } finally {
                if (listResponse != null) {
                    listResponse.close();
                }
            }
        } while (page <= totalPages);

        log.info("Cloudflare dns pull finished, configId={}, domainId={}, domain={}, zoneId={}, totalCount={}",
                config.getId(), domain.getId(), domainName, zoneId, records.size());
        return records;
    }

    @Override
    public DnsBatchChangeResponse batchChangeRecords(DnsProviderConfig config, Domain domain, DnsBatchChangeRequest request) {
        String apiToken = extractApiToken(config);
        String zoneId = extractZoneId(domain);
        String domainName = domain.getDomain();

        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("未配置 Cloudflare API Token");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("当前域名未配置 Cloudflare Zone ID");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.getDeletes() != null && !request.getDeletes().isEmpty()) {
            payload.put("deletes", request.getDeletes().stream()
                    .map(item -> Map.of("id", item.getExternalRecordId()))
                    .toList());
        }
        if (request.getPatches() != null && !request.getPatches().isEmpty()) {
            payload.put("patches", request.getPatches().stream()
                    .map(this::toCloudflarePatchPayload)
                    .toList());
        }
        if (request.getPosts() != null && !request.getPosts().isEmpty()) {
            payload.put("posts", request.getPosts().stream()
                    .map(this::toCloudflareCreatePayload)
                    .toList());
        }
        if (payload.isEmpty()) {
            throw new IllegalStateException("当前没有可推送的 DNS 变更");
        }

        log.info("Cloudflare dns push start, configId={}, domainId={}, domain={}, zoneId={}, deletes={}, patches={}, posts={}",
                config.getId(), domain.getId(), domainName, zoneId,
                request.getDeletes().size(), request.getPatches().size(), request.getPosts().size());

        Response batchResponse = null;
        try {
            batchResponse = client.target(DNS_RECORDS_BATCH_URL_TEMPLATE.formatted(zoneId))
                    .request(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                    .post(jakarta.ws.rs.client.Entity.entity(payload, MediaType.APPLICATION_JSON));
            log.info("Cloudflare dns push response received, configId={}, domainId={}, domain={}, zoneId={}, status={}",
                    config.getId(), domain.getId(), domainName, zoneId, batchResponse.getStatus());

            String body = batchResponse.readEntity(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("success").asBoolean(false)) {
                JsonNode errors = root.path("errors");
                String message = "Cloudflare DNS 记录推送失败";
                if (errors.isArray() && !errors.isEmpty()) {
                    message = errors.get(0).path("message").asText(message);
                }
                log.warn("Cloudflare dns push failed response, configId={}, domainId={}, zoneId={}, message={}",
                        config.getId(), domain.getId(), zoneId, message);
                throw new IllegalStateException(message);
            }

            DnsBatchChangeResponse response = new DnsBatchChangeResponse();
            response.setDeletedCount(request.getDeletes().size());
            response.setPatchedCount(request.getPatches().size());
            response.setPostedCount(request.getPosts().size());
            response.setMessage("Cloudflare DNS 记录推送成功");
            log.info("Cloudflare dns push finished, configId={}, domainId={}, domain={}, zoneId={}, deletedCount={}, patchedCount={}, postedCount={}",
                    config.getId(), domain.getId(), domainName, zoneId,
                    response.getDeletedCount(), response.getPatchedCount(), response.getPostedCount());
            return response;
        } catch (Exception e) {
            log.error("Cloudflare dns push failed, configId={}, domainId={}, domain={}, zoneId={}, message={}",
                    config.getId(), domain.getId(), domainName, zoneId, e.getMessage(), e);
            throw new IllegalStateException("Cloudflare DNS 记录推送失败: " + e.getMessage(), e);
        } finally {
            if (batchResponse != null) {
                batchResponse.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractApiToken(DnsProviderConfig config) {
        if (config.getCredentialJson() == null || config.getCredentialJson().isBlank()) {
            return null;
        }
        Map<String, Object> credentials = JsonUtil.toObject(config.getCredentialJson(), Map.class);
        Object apiToken = credentials.get("apiToken");
        return apiToken == null ? null : String.valueOf(apiToken);
    }

    @SuppressWarnings("unchecked")
    private String extractAccountId(DnsProviderConfig config) {
        if (config.getExtensionJson() == null || config.getExtensionJson().isBlank()) {
            return null;
        }
        Map<String, Object> extensions = JsonUtil.toObject(config.getExtensionJson(), Map.class);
        Object accountId = extensions.get("accountId");
        return accountId == null ? null : String.valueOf(accountId);
    }

    @SuppressWarnings("unchecked")
    private String extractZoneId(Domain domain) {
        if (domain.getDnsBindingExtensionJson() == null || domain.getDnsBindingExtensionJson().isBlank()) {
            return null;
        }
        Map<String, Object> extensions = JsonUtil.toObject(domain.getDnsBindingExtensionJson(), Map.class);
        Object zoneId = extensions.get("zoneId");
        return zoneId == null ? null : String.valueOf(zoneId);
    }

    private DnsProviderRecord toProviderRecord(JsonNode item) {
        DnsProviderRecord record = new DnsProviderRecord();
        record.setExternalRecordId(item.path("id").asText(null));
        record.setName(item.path("name").asText(null));
        record.setFullName(item.path("name").asText(null));
        record.setType(item.path("type").asText(null));
        record.setContent(item.path("content").asText(null));
        record.setTtl(item.path("ttl").isMissingNode() || item.path("ttl").isNull() ? null : item.path("ttl").asInt());
        record.setProxied(item.path("proxied").isMissingNode() || item.path("proxied").isNull() ? null : item.path("proxied").asBoolean());
        record.setPriority(item.path("priority").isMissingNode() || item.path("priority").isNull() ? null : item.path("priority").asInt());

        Map<String, Object> extensionMap = new LinkedHashMap<>();
        if (item.hasNonNull("comment")) {
            extensionMap.put("comment", item.path("comment").asText());
        }
        if (item.has("tags") && item.path("tags").isArray()) {
            extensionMap.put("tags", objectMapper.convertValue(item.path("tags"), List.class));
        }
        record.setExtensionJson(extensionMap.isEmpty() ? null : JsonUtil.toJsonString(extensionMap));
        record.setRawData(JsonUtil.toJsonString(objectMapper.convertValue(item, Map.class)));
        return record;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toCloudflareCreatePayload(DnsBatchChangeItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", item.getType());
        payload.put("name", resolveRecordName(item));
        payload.put("content", item.getContent());
        if (item.getTtl() != null) {
            payload.put("ttl", item.getTtl());
        }
        if (item.getProxied() != null) {
            payload.put("proxied", item.getProxied());
        }
        if (item.getPriority() != null) {
            payload.put("priority", item.getPriority());
        }
        Map<String, Object> extensionMap = parseExtensionJson(item.getExtensionJson());
        if (extensionMap.get("comment") != null) {
            payload.put("comment", extensionMap.get("comment"));
        }
        if (extensionMap.get("tags") instanceof List<?> tags && !tags.isEmpty()) {
            payload.put("tags", tags);
        }
        return payload;
    }

    private Map<String, Object> toCloudflarePatchPayload(DnsBatchChangeItem item) {
        Map<String, Object> payload = toCloudflareCreatePayload(item);
        payload.put("id", item.getExternalRecordId());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseExtensionJson(String extensionJson) {
        if (extensionJson == null || extensionJson.isBlank()) {
            return Map.of();
        }
        return JsonUtil.toObject(extensionJson, Map.class);
    }

    private String resolveRecordName(DnsBatchChangeItem item) {
        if (item.getFullName() != null && !item.getFullName().isBlank()) {
            return item.getFullName();
        }
        return item.getName();
    }
}

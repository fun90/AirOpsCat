package com.fun90.airopscat.service.dns;

import com.fun90.airopscat.model.dto.DnsProviderConfigRequest;
import com.fun90.airopscat.model.dto.DnsBatchChangeRequest;
import com.fun90.airopscat.model.dto.DnsBatchChangeResponse;
import com.fun90.airopscat.model.dto.DnsProviderRecord;
import com.fun90.airopscat.model.dto.DnsProviderTestResponse;
import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.model.entity.DnsProviderConfig;
import com.fun90.airopscat.model.enums.DnsProviderType;

import java.util.List;

public interface DnsProviderClient {

    DnsProviderType getProviderType();

    void validateConfigRequest(DnsProviderConfigRequest request);

    DnsProviderTestResponse testConnection(DnsProviderConfig config);

    List<DnsProviderRecord> listRecords(DnsProviderConfig config, Domain domain);

    DnsBatchChangeResponse batchChangeRecords(DnsProviderConfig config, Domain domain, DnsBatchChangeRequest request);
}

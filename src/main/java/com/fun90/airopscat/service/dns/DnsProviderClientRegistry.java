package com.fun90.airopscat.service.dns;

import com.fun90.airopscat.model.enums.DnsProviderType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class DnsProviderClientRegistry {

    private final Instance<DnsProviderClient> providerClients;

    @Inject
    public DnsProviderClientRegistry(Instance<DnsProviderClient> providerClients) {
        this.providerClients = providerClients;
    }

    public DnsProviderClient getClient(DnsProviderType providerType) {
        return providerClients.stream()
                .filter(client -> client.getProviderType() == providerType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported DNS provider: " + providerType));
    }
}

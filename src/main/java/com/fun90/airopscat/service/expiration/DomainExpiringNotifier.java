package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.Domain;
import com.fun90.airopscat.repository.DomainRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class DomainExpiringNotifier implements MonitorNotifier {

    @Inject
    DomainRepository domainRepository;

    @Override
    public String getType() {
        return "domain";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 域名到期提醒";
    }

    @Override
    public List<String> findItems(LocalDate today) {
        List<Domain> domains = domainRepository.findExpiringOnDate(today);
        return domains.stream()
                .map(Domain::getDomain)
                .filter(domain -> domain != null && !domain.isBlank())
                .distinct()
                .toList();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return "今日到期域名数: " + items.size() + "\n" + String.join(", ", items);
    }
}

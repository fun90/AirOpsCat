package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.Server;
import com.fun90.airopscat.repository.ServerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class ServerExpiringNotifier implements ExpiringResourceNotifier {

    @Inject
    ServerRepository serverRepository;

    @Override
    public String getType() {
        return "server";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 服务器到期提醒";
    }

    @Override
    public List<String> findExpiringItems(LocalDate today) {
        List<Server> servers = serverRepository.findExpiringOnDate(today);
        return servers.stream()
                .map(server -> {
                    String name = server.getName();
                    String ip = server.getIp();
                    if (name != null && !name.isBlank()) {
                        return name + " (" + ip + ")";
                    }
                    return ip;
                })
                .distinct()
                .toList();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return "今日到期服务器数: " + items.size() + "\n" + String.join(", ", items);
    }
}

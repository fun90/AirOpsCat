package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.service.BarkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class MonitorNotificationService {

    @Inject
    BarkService barkService;

    @Inject
    Instance<MonitorNotifier> notifiers;

    public void notify(String type) {
        LocalDate today = LocalDate.now();

        Optional<MonitorNotifier> notifierOpt = findNotifier(type);
        if (notifierOpt.isEmpty()) {
            log.warn("未找到提醒处理器: {}", type);
            return;
        }

        MonitorNotifier notifier = notifierOpt.get();
        try {
            List<String> items = notifier.findItems(today);
            if (items.isEmpty()) {
                log.info("{} 今日没有需要提醒的记录", notifier.getTitle());
                return;
            }

            String body = notifier.buildBody(items);
            barkService.sendInfoNotification(notifier.getTitle(), body);
        } catch (Exception e) {
            log.error("执行{}提醒时发生错误", notifier.getTitle(), e);
            barkService.sendErrorNotification("AirOpsCat 提醒失败",
                    "执行" + notifier.getTitle() + "时发生错误: " + e.getMessage());
        }
    }

    private Optional<MonitorNotifier> findNotifier(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        for (MonitorNotifier notifier : notifiers) {
            if (type.equalsIgnoreCase(notifier.getType())) {
                return Optional.of(notifier);
            }
        }
        return Optional.empty();
    }
}

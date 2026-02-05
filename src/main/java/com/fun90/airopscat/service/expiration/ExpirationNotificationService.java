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
public class ExpirationNotificationService {

    @Inject
    BarkService barkService;

    @Inject
    Instance<ExpiringResourceNotifier> notifiers;

    public void notifyExpiringToday(String type) {
        LocalDate today = LocalDate.now();

        Optional<ExpiringResourceNotifier> notifierOpt = findNotifier(type);
        if (notifierOpt.isEmpty()) {
            log.warn("未找到到期提醒处理器: {}", type);
            return;
        }

        ExpiringResourceNotifier notifier = notifierOpt.get();
        try {
            List<String> items = notifier.findExpiringItems(today);
            if (items.isEmpty()) {
                log.info("{} 今日没有到期记录", notifier.getTitle());
                return;
            }

            String body = notifier.buildBody(items);
            barkService.sendInfoNotification(notifier.getTitle(), body);
        } catch (Exception e) {
            log.error("执行{}到期提醒时发生错误", notifier.getTitle(), e);
            barkService.sendErrorNotification("AirOpsCat 到期提醒失败",
                    "执行" + notifier.getTitle() + "时发生错误: " + e.getMessage());
        }
    }

    private Optional<ExpiringResourceNotifier> findNotifier(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        for (ExpiringResourceNotifier notifier : notifiers) {
            if (type.equalsIgnoreCase(notifier.getType())) {
                return Optional.of(notifier);
            }
        }
        return Optional.empty();
    }
}

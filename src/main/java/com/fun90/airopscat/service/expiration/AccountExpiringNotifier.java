package com.fun90.airopscat.service.expiration;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.repository.AccountRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class AccountExpiringNotifier implements ExpiringResourceNotifier {

    @Inject
    AccountRepository accountRepository;

    @Override
    public String getType() {
        return "account";
    }

    @Override
    public String getTitle() {
        return "AirOpsCat 到期提醒";
    }

    @Override
    public List<String> findExpiringItems(LocalDate today) {
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);

        List<Account> expiringAccounts = accountRepository.findExpiringOnDate(startOfDay, endOfDay);
        return expiringAccounts.stream()
                .map(account -> {
                    String remark = account.getRemark();
                    if (remark != null && !remark.isBlank()) {
                        return remark;
                    }
                    return account.getAccountNo();
                })
                .distinct()
                .toList();
    }

    @Override
    public String buildBody(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return "今日到期账号数: " + items.size() + "\n" + String.join(", ", items);
    }
}

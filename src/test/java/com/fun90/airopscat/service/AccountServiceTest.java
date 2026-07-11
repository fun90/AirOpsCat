package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import com.fun90.airopscat.repository.AccountRepository;
import com.fun90.airopscat.service.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountServiceTest {

    @Test
    void shouldUpdateMaxIpsAndTriggerRuntimeSync() {
        Account existing = account(1L, "acct001", 2);
        AccountService service = serviceWith(existing);

        Account update = account(1L, "acct001", 5);

        Account result = service.updateAccount(update);

        assertEquals(5, result.getMaxIps());
        assertEquals(1, ((FakeRateLimitService) service.rateLimitService).triggerCount);
    }

    @Test
    void shouldClearMaxIpsAndTriggerRuntimeSync() {
        Account existing = account(1L, "acct001", 2);
        AccountService service = serviceWith(existing);

        Account update = account(1L, "acct001", null);

        Account result = service.updateAccount(update);

        assertNull(result.getMaxIps());
        assertEquals(1, ((FakeRateLimitService) service.rateLimitService).triggerCount);
    }

    private static AccountService serviceWith(Account existing) {
        AccountService service = new AccountService();
        service.accountRepository = new FakeAccountRepository(existing);
        service.rateLimitService = new FakeRateLimitService();
        return service;
    }

    private static Account account(Long id, String accountNo, Integer maxIps) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNo(accountNo);
        account.setUuid("123e4567-e89b-12d3-a456-426614174000");
        account.setMaxIps(maxIps);
        return account;
    }

    static class FakeAccountRepository extends AccountRepository {
        private final Account account;

        FakeAccountRepository(Account account) {
            this.account = account;
        }

        @Override
        public Account findById(Long id) {
            return Objects.equals(account.getId(), id) ? account : null;
        }

        @Override
        public boolean existsByUuidAndIdNot(String uuid, Long id) {
            return false;
        }

        @Override
        public void persist(Account account) {
        }
    }

    static class FakeRateLimitService extends RateLimitService {
        int triggerCount;

        @Override
        public void triggerAsyncSync() {
            triggerCount++;
        }
    }
}

package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.Account;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTrafficLimitServiceTest {

    @Test
    void shouldUseAccountSpeedWhenNotOverQuota() {
        AccountTrafficLimitService service = service(20);
        Account account = account(50);

        AccountTrafficLimitService.EffectiveSpeedLimit result = service.resolveEffectiveSpeed(account, gb(9), 10L);

        assertEquals(50, result.speed());
        assertFalse(result.trafficOverQuotaLimited());
    }

    @Test
    void shouldUseDefaultOverQuotaSpeedWhenAccountHasNoSpeed() {
        AccountTrafficLimitService service = service(20);
        Account account = account(null);

        AccountTrafficLimitService.EffectiveSpeedLimit result = service.resolveEffectiveSpeed(account, gb(10), 10L);

        assertEquals(20, result.speed());
        assertTrue(result.trafficOverQuotaLimited());
    }

    @Test
    void shouldUseConfiguredOverQuotaSpeed() {
        AccountTrafficLimitService service = service(35);
        Account account = account(null);

        AccountTrafficLimitService.EffectiveSpeedLimit result = service.resolveEffectiveSpeed(account, gb(10), 10L);

        assertEquals(35, result.speed());
        assertTrue(result.trafficOverQuotaLimited());
    }

    @Test
    void shouldNotRelaxLowerAccountSpeedWhenOverQuota() {
        AccountTrafficLimitService service = service(20);
        Account account = account(10);

        AccountTrafficLimitService.EffectiveSpeedLimit result = service.resolveEffectiveSpeed(account, gb(10), 10L);

        assertEquals(10, result.speed());
        assertFalse(result.trafficOverQuotaLimited());
    }

    @Test
    void shouldReturnUnlimitedWhenNoQuotaAndNoAccountSpeed() {
        AccountTrafficLimitService service = service(20);
        Account account = account(null);

        AccountTrafficLimitService.EffectiveSpeedLimit result = service.resolveEffectiveSpeed(account, gb(100), null);

        assertNull(result.speed());
        assertFalse(result.trafficOverQuotaLimited());
    }

    private static AccountTrafficLimitService service(int overQuotaSpeed) {
        AccountTrafficLimitService service = new AccountTrafficLimitService();
        FakeSystemConfigService configService = new FakeSystemConfigService();
        configService.intValues.put(AccountTrafficLimitService.OVER_QUOTA_SPEED_KEY, overQuotaSpeed);
        service.systemConfigService = configService;
        return service;
    }

    private static Account account(Integer speed) {
        Account account = new Account();
        account.setSpeed(speed);
        return account;
    }

    private static long gb(long value) {
        return value * 1024L * 1024L * 1024L;
    }

    static class FakeSystemConfigService extends SystemConfigService {
        final Map<String, Integer> intValues = new HashMap<>();

        FakeSystemConfigService() {
            super(null, null, null);
        }

        @Override
        public int getIntValue(String key, int defaultValue) {
            return intValues.getOrDefault(key, defaultValue);
        }
    }
}

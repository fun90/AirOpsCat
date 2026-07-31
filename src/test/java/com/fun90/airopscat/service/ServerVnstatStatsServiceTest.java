package com.fun90.airopscat.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerVnstatStatsServiceTest {

    @Test
    void shouldBuildDailyQueryForCustomPeriod() {
        String command = ServerVnstatStatsService.buildPeriodQueryCommand(
                "ens3", LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 20));

        assertEquals("vnstat -i 'ens3' --json d --begin '2026-07-31' --end '2026-08-20' 2>/dev/null",
                command);
    }

    @Test
    void shouldSumVnstat2DailyTrafficInBytes() {
        String json = """
                {
                  "jsonversion": "2",
                  "interfaces": [{
                    "traffic": {
                      "day": [
                        {"rx": 1024, "tx": 2048},
                        {"rx": 4096, "tx": 8192}
                      ]
                    }
                  }]
                }
                """;

        ServerVnstatStatsService.VnstatPeriodTotal total =
                ServerVnstatStatsService.parsePeriodTotal(json, 1L);

        assertEquals(5120L, total.rxBytes());
        assertEquals(10240L, total.txBytes());
    }

    @Test
    void shouldSupportLegacyVnstatDailyTrafficInKib() {
        String json = """
                {
                  "jsonversion": "1",
                  "interfaces": [{
                    "traffic": {
                      "days": [
                        {"rx": 2, "tx": 3},
                        {"rx": 5, "tx": 7}
                      ]
                    }
                  }]
                }
                """;

        ServerVnstatStatsService.VnstatPeriodTotal total =
                ServerVnstatStatsService.parsePeriodTotal(json, 1L);

        assertEquals(7L * 1024L, total.rxBytes());
        assertEquals(10L * 1024L, total.txBytes());
    }

    @Test
    void shouldReturnZeroWhenPeriodHasNoTraffic() {
        String json = """
                {
                  "jsonversion": "2",
                  "interfaces": [{
                    "traffic": {
                      "day": []
                    }
                  }]
                }
                """;

        ServerVnstatStatsService.VnstatPeriodTotal total =
                ServerVnstatStatsService.parsePeriodTotal(json, 1L);

        assertEquals(0L, total.rxBytes());
        assertEquals(0L, total.txBytes());
    }

    @Test
    void shouldReturnNullWhenDailyDataIsMissing() {
        String json = """
                {
                  "jsonversion": "2",
                  "interfaces": [{
                    "traffic": {}
                  }]
                }
                """;

        assertNull(ServerVnstatStatsService.parsePeriodTotal(json, 1L));
    }
}

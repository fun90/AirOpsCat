package com.fun90.airopscat.model.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainDnsRecordScheduleTest {

    private DomainDnsRecord record(String start, String end) {
        DomainDnsRecord r = new DomainDnsRecord();
        r.setScheduleStart(start);
        r.setScheduleEnd(end);
        r.setScheduleContentIn("IP1");
        r.setScheduleContentOut("IP2");
        return r;
    }

    @Test
    void 同日时段_起点含终点不含() {
        DomainDnsRecord r = record("00:00", "07:00");
        assertEquals("IP1", r.scheduledContent(LocalTime.of(0, 0)));
        assertEquals("IP1", r.scheduledContent(LocalTime.of(6, 59)));
        assertEquals("IP2", r.scheduledContent(LocalTime.of(7, 0)));
        assertEquals("IP2", r.scheduledContent(LocalTime.of(23, 59)));
    }

    @Test
    void 跨零点时段() {
        DomainDnsRecord r = record("22:00", "06:00");
        assertEquals("IP1", r.scheduledContent(LocalTime.of(23, 0)));
        assertEquals("IP1", r.scheduledContent(LocalTime.of(5, 59)));
        assertEquals("IP2", r.scheduledContent(LocalTime.of(12, 0)));
    }
}

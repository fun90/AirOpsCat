package com.fun90.airopscat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipRequestReaderInterceptorTest {

    @Test
    void shouldDetectGzipContentEncoding() {
        assertTrue(GzipRequestReaderInterceptor.isGzipEncoded(List.of("gzip")));
        assertTrue(GzipRequestReaderInterceptor.isGzipEncoded(List.of("br, gzip")));
        assertTrue(GzipRequestReaderInterceptor.isGzipEncoded(List.of("GZip")));
        assertFalse(GzipRequestReaderInterceptor.isGzipEncoded(List.of("identity")));
        assertFalse(GzipRequestReaderInterceptor.isGzipEncoded(List.of()));
    }
}

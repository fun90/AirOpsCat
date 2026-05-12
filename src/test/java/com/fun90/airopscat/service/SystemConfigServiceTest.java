package com.fun90.airopscat.service;

import com.fun90.airopscat.model.entity.SystemConfig;
import com.fun90.airopscat.repository.SystemConfigRepository;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemConfigServiceTest {

    @Test
    void shouldSupportServerMonitorAlertNotifyIntervalConfig() {
        SystemConfigService service = new SystemConfigService(new EmptySystemConfigRepository(), null, new EmptyConfig());

        int intervalMinutes = service.getIntValue("airopscat.server.monitor.alert.min-interval-minutes", 10);

        assertEquals(60, intervalMinutes);
    }

    static class EmptySystemConfigRepository extends SystemConfigRepository {
        @Override
        public Optional<SystemConfig> findOptionalByConfigKey(String configKey) {
            return Optional.empty();
        }
    }

    static class EmptyConfig implements Config {
        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            throw new IllegalArgumentException(propertyName);
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            return null;
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            return Optional.empty();
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return List.of();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new IllegalArgumentException(type.getName());
        }
    }
}

package com.fun90.airopscat.service;

import com.fun90.airopscat.model.dto.SystemConfigGroupDto;
import com.fun90.airopscat.model.dto.SystemConfigItemDto;
import com.fun90.airopscat.model.dto.SystemConfigUpdateRequest;
import com.fun90.airopscat.model.entity.SystemConfig;
import com.fun90.airopscat.repository.SystemConfigRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class SystemConfigService {

    private final SystemConfigRepository systemConfigRepository;
    private final Config config;

    private final Map<String, ConfigGroupDefinition> groupDefinitions;
    private final Map<String, ConfigItemDefinition> itemDefinitions;

    @Inject
    public SystemConfigService(SystemConfigRepository systemConfigRepository, Config config) {
        this.systemConfigRepository = systemConfigRepository;
        this.config = config;
        this.groupDefinitions = buildGroupDefinitions();
        this.itemDefinitions = indexItemDefinitions(groupDefinitions);
    }

    public List<SystemConfigGroupDto> getConfigGroups() {
        return groupDefinitions.values().stream()
                .map(this::toGroupDto)
                .toList();
    }

    public SystemConfigGroupDto getConfigGroup(String groupKey) {
        ConfigGroupDefinition definition = requireGroupDefinition(groupKey);
        return toGroupDto(definition);
    }

    @Transactional
    public void initializeDefaultConfigs() {
        for (ConfigGroupDefinition groupDefinition : groupDefinitions.values()) {
            for (ConfigItemDefinition itemDefinition : groupDefinition.items().values()) {
                initializeDefaultConfig(groupDefinition.groupKey(), itemDefinition);
            }
        }
    }

    public String getResolvedValue(String key) {
        ConfigItemDefinition definition = requireItemDefinition(key);
        return getResolvedValue(definition);
    }

    @Transactional
    public SystemConfigGroupDto saveGroup(String groupKey, SystemConfigUpdateRequest request) {
        ConfigGroupDefinition definition = requireGroupDefinition(groupKey);
        Map<String, String> values = request == null || request.getValues() == null
                ? Map.of()
                : request.getValues();

        for (ConfigItemDefinition itemDefinition : definition.items().values()) {
            saveSingleValue(definition.groupKey(), itemDefinition, values.get(itemDefinition.key()));
        }

        return toGroupDto(definition);
    }

    private void saveSingleValue(String groupKey, ConfigItemDefinition definition, String rawValue) {
        String normalizedValue = normalizeValue(rawValue, definition.inputType());
        if (definition.required() && !hasText(normalizedValue) && !isBooleanInput(definition.inputType())) {
            throw new IllegalArgumentException(definition.label() + "不能为空");
        }

        Optional<SystemConfig> optional = systemConfigRepository.findOptionalByConfigKey(definition.key());
        SystemConfig configEntity = optional.orElseGet(SystemConfig::new);
        configEntity.setConfigKey(definition.key());
        configEntity.setGroupKey(groupKey);
        configEntity.setConfigValue(normalizedValue);
        if (configEntity.getId() == null) {
            systemConfigRepository.persist(configEntity);
        }
    }

    private void initializeDefaultConfig(String groupKey, ConfigItemDefinition definition) {
        if (systemConfigRepository.findOptionalByConfigKey(definition.key()).isPresent()) {
            return;
        }

        String defaultValue = normalizeValue(definition.defaultValue(), definition.inputType());
        SystemConfig configEntity = new SystemConfig();
        configEntity.setConfigKey(definition.key());
        configEntity.setGroupKey(groupKey);
        configEntity.setConfigValue(defaultValue);
        systemConfigRepository.persist(configEntity);
    }

    private String getResolvedValue(ConfigItemDefinition definition) {
        Optional<SystemConfig> stored = systemConfigRepository.findOptionalByConfigKey(definition.key());
        if (stored.isPresent()) {
            return normalizeValue(stored.get().getConfigValue(), definition.inputType());
        }
        return normalizeValue(
                config.getOptionalValue(definition.key(), String.class).orElse(definition.defaultValue()),
                definition.inputType()
        );
    }

    private SystemConfigGroupDto toGroupDto(ConfigGroupDefinition definition) {
        List<SystemConfigItemDto> items = definition.items().values().stream()
                .map(itemDefinition -> SystemConfigItemDto.builder()
                        .key(itemDefinition.key())
                        .label(itemDefinition.label())
                        .description(itemDefinition.description())
                        .inputType(itemDefinition.inputType())
                        .required(itemDefinition.required())
                        .sensitive(itemDefinition.sensitive())
                        .placeholder(itemDefinition.placeholder())
                        .value(getResolvedValue(itemDefinition))
                        .build())
                .toList();

        return SystemConfigGroupDto.builder()
                .groupKey(definition.groupKey())
                .title(definition.title())
                .description(definition.description())
                .testSupported(definition.testSupported())
                .items(items)
                .build();
    }

    private ConfigGroupDefinition requireGroupDefinition(String groupKey) {
        ConfigGroupDefinition definition = groupDefinitions.get(groupKey);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的配置分组: " + groupKey);
        }
        return definition;
    }

    private ConfigItemDefinition requireItemDefinition(String key) {
        ConfigItemDefinition definition = itemDefinitions.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的配置项: " + key);
        }
        return definition;
    }

    private Map<String, ConfigGroupDefinition> buildGroupDefinitions() {
        Map<String, ConfigItemDefinition> barkItems = new LinkedHashMap<>();
        barkItems.put("airopscat.bark.url", new ConfigItemDefinition(
                "airopscat.bark.url",
                "Bark 地址",
                "Bark 服务地址，例如 https://push.example.com",
                "url",
                true,
                false,
                "https://push.example.com",
                ""
        ));
        barkItems.put("airopscat.bark.device-key", new ConfigItemDefinition(
                "airopscat.bark.device-key",
                "设备 Key",
                "推送目标设备的 Bark Key",
                "password",
                true,
                true,
                "请输入 Bark 设备 Key",
                ""
        ));
        barkItems.put("airopscat.bark.encrypt-enabled", new ConfigItemDefinition(
                "airopscat.bark.encrypt-enabled",
                "启用加密",
                "启用后会按 Bark 加密协议发送 ciphertext 和 iv",
                "checkbox",
                false,
                false,
                "",
                "false"
        ));
        barkItems.put("airopscat.bark.encrypt-key", new ConfigItemDefinition(
                "airopscat.bark.encrypt-key",
                "AES Key",
                "启用加密时必须为 32 字节",
                "password",
                false,
                true,
                "32字节 AES Key",
                ""
        ));
        barkItems.put("airopscat.bark.encrypt-iv", new ConfigItemDefinition(
                "airopscat.bark.encrypt-iv",
                "AES IV",
                "启用加密时必须为 16 字节",
                "password",
                false,
                true,
                "16字节 AES IV",
                ""
        ));
        barkItems.put("airopscat.bark.default-group", new ConfigItemDefinition(
                "airopscat.bark.default-group",
                "默认分组",
                "调用方未指定 group 时使用",
                "text",
                false,
                false,
                "AirOpsCat",
                "AirOpsCat"
        ));
        barkItems.put("airopscat.bark.default-sound", new ConfigItemDefinition(
                "airopscat.bark.default-sound",
                "默认铃声",
                "调用方未指定 sound 时使用",
                "text",
                false,
                false,
                "system",
                "system"
        ));
        barkItems.put("airopscat.bark.default-icon", new ConfigItemDefinition(
                "airopscat.bark.default-icon",
                "默认图标",
                "调用方未指定 icon 时使用",
                "url",
                false,
                false,
                "https://static.example.com/icon.png",
                ""
        ));

        Map<String, ConfigGroupDefinition> groups = new LinkedHashMap<>();
        groups.put("bark", new ConfigGroupDefinition(
                "bark",
                "Bark 推送",
                "维护 Bark 地址、加密参数和默认通知属性",
                true,
                barkItems
        ));
        return groups;
    }

    private Map<String, ConfigItemDefinition> indexItemDefinitions(Map<String, ConfigGroupDefinition> groups) {
        Map<String, ConfigItemDefinition> result = new LinkedHashMap<>();
        groups.values().forEach(group -> group.items().forEach(result::put));
        return result;
    }

    private String normalizeValue(String value, String inputType) {
        if (value == null) {
            return isBooleanInput(inputType) ? "false" : null;
        }
        if (isBooleanInput(inputType)) {
            return String.valueOf(Boolean.parseBoolean(value));
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBooleanInput(String inputType) {
        return Objects.equals("checkbox", inputType);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ConfigGroupDefinition(
            String groupKey,
            String title,
            String description,
            boolean testSupported,
            Map<String, ConfigItemDefinition> items
    ) {
    }

    private record ConfigItemDefinition(
            String key,
            String label,
            String description,
            String inputType,
            boolean required,
            boolean sensitive,
            String placeholder,
            String defaultValue
    ) {
    }
}

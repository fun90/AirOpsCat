package com.fun90.airopscat.config;

import com.fun90.airopscat.utils.CryptoUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * 加密字段转换器 - Quarkus版本
 * 自动处理敏感字段的加密解密，在保存时加密，查询时解密
 */
@Slf4j
@Converter(autoApply = false)
@ApplicationScoped
public class CryptoConverter implements AttributeConverter<String, String> {

    @Inject
    CryptoUtil cryptoUtil;

    /**
     * 将实体属性转换为数据库列（保存时加密）
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.trim().isEmpty()) {
            return attribute;
        }
        
        try {
            // 检查是否已经加密，避免重复加密
            if (cryptoUtil != null && !cryptoUtil.isEncrypted(attribute)) {
                return cryptoUtil.encrypt(attribute);
            }
            return attribute;
        } catch (Exception e) {
            // 如果加密失败，记录日志但不抛出异常，保持原数据
            System.err.println("加密失败: " + e.getMessage());
            return attribute;
        }
    }

    /**
     * 将数据库列转换为实体属性（查询时解密）
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return dbData;
        }
        return cryptoUtil.decrypt(dbData);
    }
}
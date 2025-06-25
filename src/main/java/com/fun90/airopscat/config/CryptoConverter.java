package com.fun90.airopscat.config;

import com.fun90.airopscat.utils.CryptoUtil;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 加密字段转换器
 * 自动处理敏感字段的加密解密，在保存时加密，查询时解密
 */
@Converter(autoApply = false)
@Component
public class CryptoConverter implements AttributeConverter<String, String> {

    @Autowired
    private CryptoUtil cryptoUtil;

    /**
     * 将实体属性转换为数据库列（保存时加密）
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (!StringUtils.hasText(attribute)) {
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
        if (!StringUtils.hasText(dbData)) {
            return dbData;
        }
        
        try {
            if (cryptoUtil != null) {
                return cryptoUtil.decrypt(dbData);
            }
            return dbData;
        } catch (Exception e) {
            // 如果解密失败，可能是明文数据，直接返回
            return dbData;
        }
    }
} 
package com.fun90.airopscat.service.core.strategy;

import com.fun90.airopscat.model.dto.CoreManagementResult;
import com.jcraft.jsch.Session;

/**
 * 内核管理策略接口
 */
public interface CoreManagementStrategy {
    
    /**
     * 启动内核服务
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult start(Session session);
    
    /**
     * 停止内核服务
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult stop(Session session);
    
    /**
     * 重启内核服务
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult restart(Session session);
    
    /**
     * 重新加载配置
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult reload(Session session);
    
    /**
     * 检查服务状态
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult status(Session session);
    
    /**
     * 验证配置文件
     * @param session SSH会话
     * @param configPath 配置文件路径
     * @return 操作结果
     */
    CoreManagementResult validateConfig(Session session, String configPath);
    
    /**
     * 安装内核
     * @param session SSH会话
     * @param version 版本号，null表示最新版本
     * @return 操作结果
     */
    CoreManagementResult install(Session session, String version);
    
    /**
     * 卸载内核
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult uninstall(Session session);
    
    /**
     * 更新内核配置
     * @param session SSH会话
     * @param configContent 配置内容
     * @param configPath 配置文件路径
     * @return 操作结果
     */
    CoreManagementResult updateConfig(Session session, String configContent, String configPath);
    
    /**
     * 获取内核版本信息
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult getVersion(Session session);
    
    /**
     * 获取内核日志
     * @param session SSH会话
     * @param lines 日志行数
     * @return 操作结果
     */
    CoreManagementResult getLogs(Session session, int lines);
    
    /**
     * 检查内核是否已安装
     * @param session SSH会话
     * @return 操作结果
     */
    CoreManagementResult isInstalled(Session session);
    
    /**
     * 获取策略名称
     * @return 策略名称
     */
    default String getStrategyName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 验证操作参数
     * @param operation 操作类型
     * @param params 参数
     * @return 验证结果
     */
    default boolean validateParams(String operation, Object... params) {
        return true;
    }
}
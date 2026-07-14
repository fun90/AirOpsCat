package com.fun90.airopscat.model.dto.guard;

import lombok.Data;

/**
 * guard-sync 在线 IP 聚合记录下的可选连接引用，用于后续按连接关闭等操作。
 */
@Data
public class GuardOnlineConnectionRefReport {
    /** 客户端 IP。 */
    private String clientIp;
    /** sing-box Clash API 连接 ID。 */
    private String connectionId;
    /** Clash API 连接开始时间。 */
    private String start;
}

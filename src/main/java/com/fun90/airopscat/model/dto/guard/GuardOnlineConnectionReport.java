package com.fun90.airopscat.model.dto.guard;

import lombok.Data;

/**
 * guard-sync 上报的单条在线连接明细，用于刷新 account_online_ip。
 */
@Data
public class GuardOnlineConnectionReport {
    /** 账户号（sing-box user name / authUser）。 */
    private String accountNo;
    /** 客户端 IP。 */
    private String clientIp;
    /** sing-box Clash API 连接 ID。 */
    private String connectionId;
    /** AirOpsCat 节点标识，如 node_123。 */
    private String nodeTag;
    /** Clash API 连接开始时间。 */
    private String start;
}

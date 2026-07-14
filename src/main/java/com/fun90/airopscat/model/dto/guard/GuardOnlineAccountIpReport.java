package com.fun90.airopscat.model.dto.guard;

import lombok.Data;

import java.util.List;

/**
 * guard-sync 上报的账号在线 IP 聚合记录，用于减少高连接数场景下的请求体大小。
 */
@Data
public class GuardOnlineAccountIpReport {
    /** 账户号（sing-box user name / authUser）。 */
    private String accountNo;
    /** AirOpsCat 节点标识，如 node_123。 */
    private String nodeTag;
    /** 该账号在该节点标识下的去重客户端 IP 列表。 */
    private List<String> clientIps;
    /** 可选连接引用；默认不上报，开启后为后续按连接关闭预留 connectionId 与 start。 */
    private List<GuardOnlineConnectionRefReport> connections;
}

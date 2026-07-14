package com.fun90.airopscat.model.dto.guard;

import lombok.Data;

import java.util.List;

/**
 * 节点侧 agent 发起的 guard-sync 请求体：上报本节点各账户的实时连接数与去重 IP 列表。
 *
 * <p>上报与配额下发合并为同一次请求往返：agent 在此请求体带本节点数据，服务端在
 * {@link GuardSyncResponse} 直接回该节点相关账户的全局配额结论。
 */
@Data
public class GuardSyncRequest {
    private String nodeIp;
    private Long generatedAtEpochSeconds;
    private List<GuardSyncAccountReport> accounts;
    private List<GuardOnlineAccountIpReport> onlineAccountIps;
}

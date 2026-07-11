package com.fun90.airopscat.model.dto.guard;

import lombok.Data;

import java.util.List;

/**
 * 单个账户在某节点上的实时统计，作为 {@link GuardSyncRequest} 的上报明细。
 */
@Data
public class GuardSyncAccountReport {
    /** 账户号（sing-box user name / authUser）。 */
    private String accountNo;
    /** 该账户在本节点上的活跃连接数。 */
    private int connections;
    /** 该账户在本节点上的去重客户端 IP 列表。 */
    private List<String> ips;
}

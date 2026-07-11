package com.fun90.airopscat.model.dto.guard;

import lombok.Data;

import java.util.Map;

/**
 * guard-sync 响应体：中心回给节点 agent 的全局配额结论，格式与 sing-box 内核读取的
 * account-quota.json 一致，agent 拿到后可直接原子写盘。
 */
@Data
public class GuardSyncResponse {
    private int schemaVersion;
    private long generatedAtEpochSeconds;
    private int ttlSeconds;
    /** accountNo -> 阻断明细；仅包含被判定超出跨节点总限的账户（黑名单语义）。 */
    private Map<String, GuardBlockedEntry> blockedAccounts;
}

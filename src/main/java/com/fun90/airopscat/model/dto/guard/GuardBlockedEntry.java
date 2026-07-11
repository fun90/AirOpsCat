package com.fun90.airopscat.model.dto.guard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 被判定超出跨节点总限的账户明细。
 *
 * <p>reason 取值："connections"（超总连接数）或 "ips"（超总去重 IP 数）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuardBlockedEntry {
    private String reason;
    private int total;
    private int limit;
}

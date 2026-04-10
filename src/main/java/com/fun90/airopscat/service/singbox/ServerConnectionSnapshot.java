package com.fun90.airopscat.service.singbox;

import java.time.Instant;
import java.util.Map;

/**
 * 服务器连接快照：记录某次轮询时的时间点及各活跃连接的流量基线。
 * 供流量统计增量计算、限速等功能复用。
 *
 * @param lastPollTime 本次轮询时间，下次轮询用于判断新旧连接
 * @param connections  活跃连接的流量基线，key=connectionId，value=[upload, download]
 */
public record ServerConnectionSnapshot(Instant lastPollTime, Map<String, long[]> connections) {}

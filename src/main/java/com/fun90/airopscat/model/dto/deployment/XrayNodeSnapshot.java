package com.fun90.airopscat.model.dto.deployment;
 
import java.util.List;
 
public record XrayNodeSnapshot(
        Long id,
        Integer port,
        Integer disabled,
        String inbound,
        Long outId,
        String tag,
        String outTag,
        String outInbound,
        String outServerIp,
        Integer outPort,
        List<VlessClient> clients
) {}
 
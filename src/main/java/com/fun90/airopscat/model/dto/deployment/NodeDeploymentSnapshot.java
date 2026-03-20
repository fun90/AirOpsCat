package com.fun90.airopscat.model.dto.deployment;

import java.util.List;

public record NodeDeploymentSnapshot(
        Long id,
        String coreType,
        String protocol,
        Integer port,
        Integer disabled,
        String inbound,
        Long outId,
        String tag,
        String outCoreType,
        String outProtocol,
        String outTag,
        String outInbound,
        String outServerIp,
        Integer outPort,
        List<VlessClient> clients
) {}

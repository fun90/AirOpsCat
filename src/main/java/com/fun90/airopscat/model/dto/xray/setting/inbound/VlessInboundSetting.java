package com.fun90.airopscat.model.dto.xray.setting.inbound;

import com.fun90.airopscat.model.dto.xray.setting.InboundSetting;
import com.fun90.airopscat.model.dto.xray.setting.user.VlessClient;

import java.util.List;

public class VlessInboundSetting extends InboundSetting {

    private List<VlessClient> clients;
}

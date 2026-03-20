package com.fun90.airopscat.service.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fun90.airopscat.model.dto.deployment.NodeDeploymentSnapshot;
import com.fun90.airopscat.model.dto.deployment.ServerSnapshot;
import com.fun90.airopscat.model.dto.deployment.VlessClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CoreConfigBuilderTest {

    @Inject
    SingBoxConfigBuilder singBoxConfigBuilder;

    @Inject
    XrayConfigBuilder xrayConfigBuilder;

    @Test
    void shouldBuildSingBoxConfigForProxyAndLandingNodes() {
        NodeDeploymentSnapshot proxyNode = new NodeDeploymentSnapshot(
                1L,
                "sing-box",
                "vless",
                443,
                0,
                """
                {
                  "type": "vless",
                  "users": [],
                  "tls": {
                    "enabled": true,
                    "server_name": "example.com"
                  },
                  "transport": {
                    "type": "tcp"
                  }
                }
                """,
                2L,
                "node_1",
                "sing-box",
                "shadowsocks",
                "node_2",
                """
                {
                  "type": "shadowsocks",
                  "method": "2022-blake3-aes-128-gcm",
                  "password": "landing-password"
                }
                """,
                "1.2.3.4",
                8443,
                List.of(new VlessClient("uuid-1", "account-1", "xtls-rprx-vision"))
        );

        String config = singBoxConfigBuilder.build(new ServerSnapshot("{}"), List.of(proxyNode));
        JsonNode root = com.fun90.airopscat.util.JsonUtil.toJsonNode(config);

        JsonNode inbound = root.path("inbounds").get(0);
        assertEquals("node_1", inbound.path("tag").asText());
        assertEquals(443, inbound.path("listen_port").asInt());
        assertEquals("uuid-1", inbound.path("users").get(0).path("uuid").asText());

        JsonNode outbounds = root.path("outbounds");
        assertEquals("default-direct", outbounds.get(0).path("tag").asText());
        assertEquals("node_2", outbounds.get(2).path("tag").asText());
        assertEquals("1.2.3.4", outbounds.get(2).path("server").asText());

        JsonNode rule = root.path("route").path("rules").get(0);
        assertEquals("node_1", rule.path("inbound").get(0).asText());
        assertEquals("node_2", rule.path("outbound").asText());
    }

    @Test
    void shouldBuildXrayConfigWithManagedClients() {
        NodeDeploymentSnapshot proxyNode = new NodeDeploymentSnapshot(
                3L,
                "xray",
                "vless",
                10443,
                0,
                """
                {
                  "protocol": "vless",
                  "settings": {
                    "clients": []
                  },
                  "streamSettings": {
                    "network": "tcp"
                  }
                }
                """,
                4L,
                "node_3",
                "xray",
                "socks",
                "node_4",
                """
                {
                  "protocol": "socks",
                  "settings": {
                    "accounts": [
                      {
                        "user": "demo",
                        "pass": "secret"
                      }
                    ]
                  }
                }
                """,
                "5.6.7.8",
                9000,
                List.of(new VlessClient("uuid-2", "account-2", "xtls-rprx-vision"))
        );

        String config = xrayConfigBuilder.build(new ServerSnapshot("{}"), List.of(proxyNode));
        JsonNode root = com.fun90.airopscat.util.JsonUtil.toJsonNode(config);

        JsonNode inbound = root.path("inbounds").get(1);
        assertEquals("node_3", inbound.path("tag").asText());
        assertEquals("uuid-2", inbound.path("settings").path("clients").get(0).path("id").asText());

        JsonNode outbound = root.path("outbounds").get(3);
        assertEquals("node_4", outbound.path("tag").asText());
        assertEquals("5.6.7.8", outbound.path("settings").path("servers").get(0).path("address").asText());

        JsonNode rule = root.path("routing").path("rules").get(2);
        assertEquals("node_3", rule.path("inboundTag").get(0).asText());
        assertEquals("node_4", rule.path("outboundTag").asText());
        assertTrue(root.path("outbounds").size() >= 4);
    }
}

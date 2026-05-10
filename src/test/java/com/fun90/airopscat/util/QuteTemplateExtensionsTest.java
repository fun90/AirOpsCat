package com.fun90.airopscat.util;

import com.fun90.airopscat.model.dto.NodeDto;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class QuteTemplateExtensionsTest {

    @Inject
    Engine engine;

    @Test
    void shouldUrlEncodeValuesInRuntimeTemplate() {
        NodeDto node = new NodeDto();
        node.setName("node A,1");
        node.setInbound(Map.of(
                "tls", Map.of(
                        "server_name", "sni.example.com",
                        "reality", Map.of(
                                "public_key", "abc+/=",
                                "short_id", List.of("01 ab")
                        )
                ),
                "obfs", Map.of("password", "pa ss/+")
        ));

        String result = engine.parse("{node.name.urlEncode}|{node.inbound.tls.server_name.urlEncode}|{node.inbound.tls.reality.public_key.urlEncode}|{node.inbound.tls.reality.short_id[0].urlEncode}|{node.inbound.obfs.password.urlEncode}")
                .data("node", node)
                .render();

        assertEquals("node%20A%2C1|sni.example.com|abc%2B%2F%3D|01%20ab|pa%20ss%2F%2B", result);
    }
}

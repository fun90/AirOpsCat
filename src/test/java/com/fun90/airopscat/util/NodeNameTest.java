package com.fun90.airopscat.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NodeNameTest {
    @Test
    void appendsDefaultNumberOnlyWhenMissing() {
        assertEquals("香港-1", NodeName.withNumber(" 香港 "));
        assertEquals("香港-12", NodeName.withNumber("香港-12"));
        assertEquals("香港-12", NodeName.withNumber(NodeName.withNumber("香港-12")));
        assertEquals("香港-备用-1", NodeName.withNumber("香港-备用"));
        assertNull(NodeName.withNumber(null));
        assertEquals("", NodeName.withNumber(""));
        assertEquals("香港-备用", NodeName.base("香港-备用-12"));
        assertEquals(BigInteger.valueOf(12), NodeName.number("香港-备用-12"));
        assertEquals(BigInteger.ONE, NodeName.number("香港-备用"));
        assertEquals(new BigInteger("12345678901234567890"), NodeName.number("香港-12345678901234567890"));
    }
}

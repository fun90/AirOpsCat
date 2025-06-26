package com.fun90.airopscat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RandomHexGeneratorTest {

    @Test
    public void testGenerateRandomHex() {
        String hex = RandomHexGenerator.generateRandomHex();
        System.out.println(hex);
        assertEquals(hex.length(), 16);
    }
}
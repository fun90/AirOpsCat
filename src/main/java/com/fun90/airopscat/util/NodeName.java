package com.fun90.airopscat.util;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NodeName {
    private static final Pattern NUMBER = Pattern.compile("-(\\d+)$");

    private NodeName() {
    }

    public static String withNumber(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String trimmed = name.trim();
        return NUMBER.matcher(trimmed).find() ? trimmed : trimmed + "-1";
    }

    public static String base(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return NUMBER.matcher(withNumber(name)).replaceFirst("");
    }

    public static BigInteger number(String name) {
        if (name == null || name.isBlank()) {
            return BigInteger.ONE;
        }
        Matcher matcher = NUMBER.matcher(withNumber(name));
        return matcher.find() ? new BigInteger(matcher.group(1)) : BigInteger.ONE;
    }
}

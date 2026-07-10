package com.fun90.airopscat.config;

public final class SshSmokeTestMode {

    public static final String ENABLED_PROPERTY = "airopscat.ssh-smoke.enabled";

    private SshSmokeTestMode() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }
}

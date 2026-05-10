package com.fun90.airopscat.util;

import io.quarkus.qute.TemplateExtension;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class QuteTemplateExtensions {

    private QuteTemplateExtensions() {
    }

    @TemplateExtension(matchName = "urlEncode")
    static String urlEncode(Object value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
    }
}

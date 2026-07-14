package com.fun90.airopscat.config;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 支持客户端以 Content-Encoding: gzip 提交 JSON 请求体。
 */
@Provider
@Priority(Priorities.ENTITY_CODER)
public class GzipRequestReaderInterceptor implements ReaderInterceptor {

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        if (isGzipEncoded(context.getHeaders().get(HttpHeaders.CONTENT_ENCODING))) {
            context.setInputStream(new GZIPInputStream(context.getInputStream()));
            context.getHeaders().remove(HttpHeaders.CONTENT_ENCODING);
        }
        return context.proceed();
    }

    static boolean isGzipEncoded(List<String> encodings) {
        if (encodings == null || encodings.isEmpty()) {
            return false;
        }
        for (String encoding : encodings) {
            if (encoding == null) {
                continue;
            }
            for (String part : encoding.split(",")) {
                if ("gzip".equals(part.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}

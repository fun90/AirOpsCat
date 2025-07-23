package com.fun90.airopscat.config;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    @Inject
    @Location("error/404")
    Template error404;

    @Inject
    @Location("error/500")
    Template error500;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "AirOpsCat")
    String appName;

    @Override
    public Response toResponse(Exception exception) {
        
        // 处理404错误
        if (exception instanceof NotFoundException) {
            log.debug("404 Not Found: {}", exception.getMessage());
            TemplateInstance template = error404.data("appName", appName);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(template.render())
                    .type(MediaType.TEXT_HTML)
                    .build();
        }
        
        // 处理其他WebApplicationException
        if (exception instanceof WebApplicationException webEx) {
            int status = webEx.getResponse().getStatus();
            
            if (status == 500) {
                log.error("Internal Server Error", exception);
                TemplateInstance template = error500.data("appName", appName);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(template.render())
                        .type(MediaType.TEXT_HTML)
                        .build();
            }
            
            // 其他HTTP错误状态码
            return webEx.getResponse();
        }
        
        // 处理未预期的服务器错误
        log.error("Unexpected server error", exception);
        TemplateInstance template = error500.data("appName", appName);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(template.render())
                .type(MediaType.TEXT_HTML)
                .build();
    }
}
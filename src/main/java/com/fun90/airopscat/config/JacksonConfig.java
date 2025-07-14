package com.fun90.airopscat.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class JacksonConfig implements ObjectMapperCustomizer {
    
    @Override
    public void customize(ObjectMapper mapper) {
        // Handle Java 8 date/time types
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Handle Hibernate lazy loading
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // mapper.registerModule(new Hibernate5Module()); // Add if needed for Hibernate

        // Additional common configurations
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
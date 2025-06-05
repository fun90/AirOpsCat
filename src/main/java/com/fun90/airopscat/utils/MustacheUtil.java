package com.fun90.airopscat.utils;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

public class MustacheUtil {
    
    private static final MustacheFactory mustacheFactory = new DefaultMustacheFactory();
    
    /**
     * 使用Mustache处理模板
     */
    public static String processTemplate(String templateContent, Map<String, Object> variables) {
        Mustache mustache = mustacheFactory.compile(new StringReader(templateContent), "template");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, variables);
        return writer.toString();
    }



    /**
     * 使用Mustache处理模板
     */
    public static String processTemplate(Reader reader, Map<String, Object> variables) {
        Mustache mustache = mustacheFactory.compile(reader, "template");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, variables);
        return writer.toString();
    }
}
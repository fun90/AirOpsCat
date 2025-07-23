package com.fun90.airopscat;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.util.TimeZone;

@QuarkusMain
public class AirOpsCatApplication implements QuarkusApplication {

    @Override
    public int run(String... args) throws Exception {
        // 设置时区
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        
        Quarkus.waitForExit();
        return 0;
    }

    public static void main(String[] args) {
        Quarkus.run(AirOpsCatApplication.class, args);
    }
}
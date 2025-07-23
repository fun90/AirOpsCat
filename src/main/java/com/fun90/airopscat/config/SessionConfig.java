//package com.fun90.airopscat.config;
//
//import io.quarkus.runtime.StartupEvent;
//import io.vertx.core.Vertx;
//import io.vertx.ext.web.Router;
//import io.vertx.ext.web.handler.SessionHandler;
//import io.vertx.ext.web.sstore.LocalSessionStore;
//import jakarta.enterprise.context.ApplicationScoped;
//import jakarta.enterprise.event.Observes;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * Session 配置类
// * 配置 Vert.x Web 的 SessionHandler
// */
//@Slf4j
//@ApplicationScoped
//public class SessionConfig {
//
//    /**
//     * 在应用启动时配置 SessionHandler
//     */
//    void configureSession(@Observes StartupEvent event, Router router, Vertx vertx) {
//        log.info("Configuring Vert.x Web SessionHandler");
//
//        // 创建本地Session存储
//        LocalSessionStore sessionStore = LocalSessionStore.create(vertx);
//
//        // 创建SessionHandler
//        SessionHandler sessionHandler = SessionHandler.create(sessionStore);
//
//        // 为所有路由添加SessionHandler
//        router.route().order(0).handler(sessionHandler);
//    }
//}
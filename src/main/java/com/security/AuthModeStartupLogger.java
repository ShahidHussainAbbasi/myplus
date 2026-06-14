package com.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the active authentication mode once at startup so it's obvious whether the monolith is
 * verifying credentials locally or delegating to the auth-service. Avoids confusion like
 * "login still works when the microservices are down" (= local mode).
 */
@Component
public class AuthModeStartupLogger {

    private static final Logger LOG = LoggerFactory.getLogger(AuthModeStartupLogger.class);

    @Value("${auth.mode:local}")
    private String authMode;

    @Value("${auth.server.url:}")
    private String authServerUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void logAuthMode() {
        if ("server".equalsIgnoreCase(authMode)) {
            LOG.info("==============================================================");
            LOG.info("  AUTH MODE = SERVER  ->  login delegated to auth-service (JWT)");
            LOG.info("  auth.server.url = {}", authServerUrl);
            LOG.info("  auth-service must be reachable; there is NO local DB fallback");
            LOG.info("==============================================================");
        } else {
            LOG.warn("==============================================================");
            LOG.warn("  AUTH MODE = LOCAL  ->  authenticating against the local DB");
            LOG.warn("  auth-service is NOT used for login (set auth.mode=server to enable)");
            LOG.warn("==============================================================");
        }
    }
}

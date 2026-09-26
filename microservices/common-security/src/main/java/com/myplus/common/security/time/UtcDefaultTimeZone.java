package com.myplus.common.security.time;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * TZ-1 — every service runs on UTC, whatever machine it runs on.
 *
 * <p>The containers already defaulted to UTC (no zone in the image); a service started on the Windows host
 * defaulted to Pakistan time. The SAME code therefore produced two different in-memory clocks, and the web layer
 * ({@link RenderZoneWebConfig}) could not know which zone an unmarked {@code LocalDateTime} was in. Pinned here —
 * before Spring builds Hibernate, Jackson or any bean — the answer is always UTC.
 *
 * <p>Stored data is unaffected: the JDBC URL fixes the session at {@code +05:00} and the driver converts on the
 * way in and out, exactly as it already did in the containers. Registered in META-INF/spring.factories.
 */
public class UtcDefaultTimeZone implements EnvironmentPostProcessor, Ordered {

    /** OFF until TZ-1 P1 lands with its gate — see the class note. */
    public static final String PROPERTY = "app.tz.pin-utc";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        /*
         * ⚠ GUARDED, DEFAULT OFF. Unguarded, this changed the JVM zone of EVERY service with common-security on its
         * classpath the moment the jar contained it — including services run on the (PKT) Windows host and every
         * `mvn test` there — while TZ-1 was described as paused. A global zone change must land deliberately, with
         * its gate, by setting app.tz.pin-utc=true (config-server), not by whoever next rebuilds a jar.
         */
        if (!Boolean.parseBoolean(environment.getProperty(PROPERTY, "false"))) return;
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Right AFTER the config files and config-server are loaded (so the switch above can live in application.yml or
     * config-server) and still before any bean is built — every EnvironmentPostProcessor runs before the context.
     */
    @Override
    public int getOrder() {
        return org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}

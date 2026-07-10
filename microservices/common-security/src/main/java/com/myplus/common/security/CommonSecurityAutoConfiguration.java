package com.myplus.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Registers the shared {@link HeaderAuthFilter} for any servlet web application that has this
 * module on its classpath. Reactive applications (e.g. the gateway) are skipped via the
 * SERVLET condition. A service may still define its own {@code HeaderAuthFilter} bean to
 * override the default.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HeaderAuthFilter headerAuthFilter() {
        return new HeaderAuthFilter();
    }

    /**
     * Stamps tenant/user identity (X-Org-Id / X-User-Id) onto logs (MDC), the current span, and
     * OpenTelemetry baggage so telemetry is filterable per tenant. No-op when no OTel SDK is present.
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantTelemetryFilter tenantTelemetryFilter() {
        return new TenantTelemetryFilter();
    }

    /**
     * Server-side XSS input sanitization (defense-in-depth). Auto-registered for every servlet
     * service on the classpath; a service may override by defining its own bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public XssSanitizingFilter xssSanitizingFilter() {
        return new XssSanitizingFilter();
    }

    /**
     * Stateless services authenticate via {@link HeaderAuthFilter} (X-Org-Id / JWT propagated by
     * the gateway), not username/password. Without any {@code UserDetailsService} on the classpath,
     * Spring Boot's {@code UserDetailsServiceAutoConfiguration} creates a default {@code user} and
     * prints a random "Using generated security password" on every startup. Registering an empty
     * {@link InMemoryUserDetailsManager} (no users) suppresses that default account.
     *
     * <p>Guarded by {@code @ConditionalOnMissingBean(UserDetailsService.class)} so services that
     * own a real user store (e.g. auth-service's {@code CustomUserDetailsService}) keep theirs.
     */
    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    public UserDetailsService emptyUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}

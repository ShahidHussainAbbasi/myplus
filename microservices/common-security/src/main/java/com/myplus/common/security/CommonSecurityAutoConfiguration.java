package com.myplus.common.security;

import org.springframework.beans.factory.annotation.Value;
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
     * Push this service's {@code service.internal-secret} into {@link GatewayIdentityForwarding} so background/relay
     * calls (which have no inbound request to copy X-Internal-Secret from) still authenticate against callees that
     * ENFORCE the secret. Returns a trivial marker bean; the side effect is the configuration.
     */
    @Bean
    public GatewayForwardingSecret gatewayForwardingSecret(@Value("${service.internal-secret:}") String internalSecret) {
        GatewayIdentityForwarding.configureInternalSecret(internalSecret);
        return new GatewayForwardingSecret();
    }

    /** Marker for the {@link GatewayIdentityForwarding} internal-secret wiring. */
    public static final class GatewayForwardingSecret {}

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
     * Slice 3.1b — denies portal principals every path this service has not explicitly allowlisted.
     *
     * <p>Auto-registered for EVERY servlet service, and <b>fails closed</b>: with no
     * {@code myplus.portal.allowlist} configured, a portal session reaches nothing here. That is the
     * property which makes portal sign-in safe to add without auditing all thirteen services — a service
     * that has never heard of the portal denies it by doing nothing.
     *
     * <p>Education sets {@code myplus.portal.allowlist=/portal/**}.
     */
    @Bean
    @ConditionalOnMissingBean
    public PortalScopeFilter portalScopeFilter(
            @Value("${myplus.portal.allowlist:}") String allowlist,
            @Value("${myplus.portal.confined-roles:}") String confinedRoles) {
        return new PortalScopeFilter(PortalScopeFilter.parseAllowlist(allowlist),
                PortalScopeFilter.parseAllowlist(confinedRoles));
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

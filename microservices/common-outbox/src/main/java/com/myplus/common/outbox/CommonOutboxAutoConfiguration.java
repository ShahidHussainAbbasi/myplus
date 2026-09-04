package com.myplus.common.outbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers the shared {@link OutboxRelay} for any service with this module on its classpath.
 *
 * Needed because {@code OutboxRelay} lives in {@code com.myplus.common.outbox}, which is outside every
 * consumer's {@code @ComponentScan} root ({@code com.myplus.business_service}, {@code com.myplus.education}, …) —
 * so component scanning alone would never find it. Registered through
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports instead, exactly like
 * {@code CommonSettingsAutoConfiguration}, so a consumer needs no scan-path change.
 *
 * Unconditional on purpose (unlike common-settings, which needs a store bean): the relay is a stateless helper
 * holding no resources, and a service that never injects it pays nothing.
 */
@AutoConfiguration
@Import(OutboxRelay.class)
public class CommonOutboxAutoConfiguration {

    /**
     * D-6 — the health endpoint, and ONLY for a service that declares its outboxes.
     *
     * <h3>⚠ Conditional on the registry bean, deliberately</h3>
     * Twelve services carry this module. Four own an outbox. Registering a controller and a JDBC service in
     * the other eight would put an unreachable {@code /outbox-health} on each of them, answering an empty
     * list — which reads to a caller exactly like "this service has nothing failing", the one sentence this
     * slice exists to stop being said wrongly. No registry, no endpoint.
     *
     * <h3>⚠ @Import, because this module is NOT component-scanned</h3>
     * It lives in {@code com.myplus.common.outbox}, outside every consumer's scan root. An
     * {@code @RestController} annotation alone registers NOTHING here — the failure that left C1's
     * {@code CapabilityService} correct, tested and unreachable, and C3's catalog missing. Anything new in
     * this package must be added below.
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(OutboxHealthRegistry.class)
    public OutboxHealthService outboxHealthService(javax.sql.DataSource dataSource,
                                                   OutboxHealthRegistry registry) {
        return new OutboxHealthService(dataSource, registry);
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(OutboxHealthRegistry.class)
    public OutboxHealthController outboxHealthController(
            OutboxHealthService health,
            org.springframework.beans.factory.ObjectProvider<OutboxRedriveAudit> audit,
            org.springframework.core.env.Environment environment) {
        return new OutboxHealthController(health, audit, environment);
    }
}

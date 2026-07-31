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
}

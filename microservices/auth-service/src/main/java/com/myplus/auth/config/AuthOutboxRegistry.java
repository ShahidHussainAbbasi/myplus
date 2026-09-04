package com.myplus.auth.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.myplus.common.outbox.OutboxHealthRegistry;

/**
 * D-6 — the outboxes this service owns.
 *
 * <p>E4's control-plane trail. Its 8 failures are gate leftovers rather than lost business events —
 * which is only knowable because someone looked, which is the point.
 *
 * <p>Declaring this bean is what switches the shared {@code /outbox-health} endpoint on: the
 * auto-configuration is conditional on it, so the eight services with no outbox get no endpoint rather than
 * one answering an empty list — which would read exactly like "nothing is failing here".
 *
 * <p>⚠ This list is also the ALLOW-LIST for the re-drive: the endpoint takes a table name and
 * {@code OutboxHealthService} validates it against exactly this, so nothing else is reachable.
 */
@Configuration
public class AuthOutboxRegistry {

    @Bean
    public OutboxHealthRegistry outboxHealthRegistry() {
        return () -> List.of("audit_outbox");
    }
}

package com.myplus.business_service.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.myplus.common.outbox.OutboxHealthRegistry;

/**
 * D-6 — the outboxes this service owns.
 *
 * <p>Audit #6 and the GL outbox (#4). The GL one is the reason this slice exists: business has
 * one dead-lettered SALE, and education's equivalent lost 56 fee postings.
 *
 * <p>Declaring this bean is what switches the shared {@code /outbox-health} endpoint on: the
 * auto-configuration is conditional on it, so the eight services with no outbox get no endpoint rather than
 * one answering an empty list — which would read exactly like "nothing is failing here".
 *
 * <p>⚠ This list is also the ALLOW-LIST for the re-drive: the endpoint takes a table name and
 * {@code OutboxHealthService} validates it against exactly this, so nothing else is reachable.
 */
@Configuration
public class BusinessOutboxRegistry {

    @Bean
    public OutboxHealthRegistry outboxHealthRegistry() {
        return () -> List.of("audit_outbox", "gl_outbox");
    }
}

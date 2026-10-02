package com.myplus.business_service.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.myplus.business_service.entity.Purchase;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;

/**
 * FP-2 — sees every saved purchase (registered on {@link Purchase} with {@code @EntityListeners}). It only hands the
 * purchase to {@link PayableOutboxService#changed}, which captures the figures and writes the outbox row before
 * commit — never an EntityManager call inside a lifecycle callback.
 *
 * <p>Spring Boot configures Hibernate's bean container, so this listener is a Spring bean; the service is resolved
 * lazily through an {@link ObjectProvider} so the entity's metadata can be built before the service exists.
 */
@Component
public class PurchasePayableListener {

    private final ObjectProvider<PayableOutboxService> payables;

    public PurchasePayableListener(ObjectProvider<PayableOutboxService> payables) {
        this.payables = payables;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PurchasePayableListener.class);

    @PostPersist
    @PostUpdate
    public void onSaved(Purchase p) {
        PayableOutboxService s = payables.getIfAvailable();
        LOG.debug("purchase {} saved (vendor {}); payables service {}", p.getPurchaseId(), p.getVenderId(),
                s == null ? "ABSENT" : "present");
        if (s != null) s.changed(p);
    }
}

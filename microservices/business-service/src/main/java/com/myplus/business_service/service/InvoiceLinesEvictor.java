package com.myplus.business_service.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.myplus.business_service.entity.Payment;
import com.myplus.business_service.entity.Sell;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * INV-CACHE — evicts a printed invoice's cached lines whenever anything they are built from is written: a sale line or a
 * payment. An ENTITY LISTENER rather than a call in each service, because it cannot be forgotten: the next writer added
 * to these tables evicts without anyone remembering to (the PurchasePayableListener pattern).
 *
 * <p>{@code SellBatch} needs no listener of its own: its only writer (SagaSaleWriter.recordBatches) writes a batch in the
 * same transaction as its sale line, and the line's eviction runs again after that transaction completes. (A lookup from
 * a batch to its invoice would mean calling the EntityManager inside a flush callback, which JPA forbids.)
 */
@Component
public class InvoiceLinesEvictor {

    private final ObjectProvider<InvoiceLinesCache> cache;

    public InvoiceLinesEvictor(ObjectProvider<InvoiceLinesCache> cache) {
        this.cache = cache;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        InvoiceLinesCache c = cache.getIfAvailable();
        if (c == null || entity == null) return;
        if (entity instanceof Sell s) {
            if (s.getCustomerHistory() != null) c.evict(s.getOrganizationId(), s.getCustomerHistory().getCustomer_history_id());
        } else if (entity instanceof Payment p) {
            c.evict(p.getOrganizationId(), p.getCustomerHistoryId());
        }
    }
}

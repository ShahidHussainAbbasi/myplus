package com.myplus.business_service.service.gl;

import com.myplus.commerce.contracts.dto.PostingEventRequest;

/**
 * The GL delivery seam — the "last hop" that carries a posting event to the General Ledger. The outbox
 * ({@code GlOutboxService}) owns durability + retry; this interface owns only the transport, so it can be swapped
 * (HTTP today → Redis Streams / Rabbit / Kafka later) by adding an impl and flipping {@code gl.publisher} — with
 * no change to the outbox, producers, or the relay. Identity is passed explicitly so each transport decides how to
 * carry the tenant (HTTP → gateway headers via runAs; a broker → message headers).
 */
public interface GlEventPublisher {

    /** Deliver one posting event to the GL. Throws on failure so the outbox can retry (must not swallow). */
    void publish(PostingEventRequest req, Long userId, Long organizationId);

    /** Whether a downstream GL transport is wired; if false, events stay PENDING for a later attempt. */
    boolean isAvailable();
}

package com.myplus.education.service;

import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxEntry;
import com.myplus.common.outbox.OutboxRelay;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one outbox delivery in its own transaction — a separate bean on purpose.
 *
 * <h3>Why this is not just a method on {@link EduNotifyService}</h3>
 *
 * The delivery hook needs BOTH {@code @Async} (so nothing sends on the request thread — slice 3.5's D3) and
 * {@code @Transactional(REQUIRES_NEW)} (so the relay's status write has a transaction of its own, the
 * caller's having already committed).
 *
 * <p>Putting both annotations on one method makes the behaviour depend on which advisor Spring happens to
 * order first. If the transaction advisor wins, the transaction is begun on the CALLING thread and the work
 * is then handed to another thread — a transaction and its connection used across two threads, which is
 * unsafe and fails in ways that are hard to reproduce. Splitting the two concerns across two beans means
 * each boundary is applied by its own proxy, in an order that cannot be ambiguous: async first at the
 * listener, transaction here.
 *
 * <p>Keeping it explicit rather than clever: this path had just cost a long debugging session, and a
 * correctness question that depends on advisor ordering is not one worth leaving open.
 */
@Component
@RequiredArgsConstructor
public class NotifyDeliveryRunner {

    private final OutboxRelay relay;

    /**
     * Deliver one row, in a fresh transaction.
     *
     * <p>The channel is passed in rather than injected: it belongs to the service that owns the outbox, and
     * this runner deliberately knows nothing about notifications, email, or education. It only knows how to
     * give a relay a transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <E extends OutboxEntry> void deliver(OutboxDelivery<E> channel, Long id) {
        relay.deliver(channel, id);
    }
}

package com.myplus.common.outbox;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The shared transactional-outbox delivery state machine (extracted from the #4 GL outbox + #6 audit outbox — one
 * implementation, reused). Idempotent per row (POSTED/FAILED are skipped), retries with an attempt counter, and
 * dead-letters after {@link #MAX_ATTEMPTS}. Transport + payload are supplied by an {@link OutboxDelivery} channel
 * (Strategy); this class knows nothing domain-specific. Callers keep their own enqueue (in-tx) + AFTER_COMMIT hook +
 * {@code @Scheduled} relay, delegating the per-row delivery here.
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ATTEMPTS = 20;

    /** Deliver one row via the channel. No-op if already POSTED/FAILED or the transport is unavailable. */
    public <E extends OutboxEntry> void deliver(OutboxDelivery<E> channel, Long id) {
        E o = channel.find(id).orElse(null);
        if (o == null || "POSTED".equals(o.getStatus()) || "FAILED".equals(o.getStatus())) return;
        if (!channel.available()) return;
        try {
            channel.send(o);
            o.setStatus("POSTED");
            o.setUpdatedAt(LocalDateTime.now());
            channel.save(o);
        } catch (Exception ex) {
            o.setAttempts((o.getAttempts() == null ? 0 : o.getAttempts()) + 1);
            o.setLastError(String.valueOf(ex.getMessage()));
            if (o.getAttempts() >= MAX_ATTEMPTS) o.setStatus("FAILED");   // dead-letter for manual review
            o.setUpdatedAt(LocalDateTime.now());
            channel.save(o);
            LOG.warn("{} outbox delivery failed for entry {} (attempt {}); will retry", channel.name(), id, o.getAttempts(), ex);
        }
    }

    /** Re-drive all currently-undelivered rows for the channel (the @Scheduled relay's body). */
    public <E extends OutboxEntry> void flush(OutboxDelivery<E> channel) {
        for (E o : channel.pending()) deliver(channel, o.getId());
    }
}

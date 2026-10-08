package com.myplus.common.outbox;

import java.util.List;
import java.util.Optional;

/**
 * The per-producer transport strategy for {@link OutboxRelay}: how to load/persist rows and how to actually deliver
 * one (the domain-specific client call, including any identity forwarding). The relay owns the reliable-delivery
 * state machine; a channel owns *what* is delivered and *where*. Open for extension — a new producer (finance,
 * inventory, …) supplies a channel without touching the relay.
 *
 * @param <E> the producer's outbox row type
 */
public interface OutboxDelivery<E extends OutboxEntry> {

    /** Short label for logs (e.g. "GL", "Audit"). */
    String name();

    /** Whether the downstream transport is wired; if false, rows stay PENDING for a later attempt. */
    boolean available();

    Optional<E> find(Long id);

    /** Undelivered rows to re-drive (typically top-N PENDING by id). */
    List<E> pending();

    E save(E entry);

    /** Deliver one row downstream. Throws on failure so the relay can retry; owns identity forwarding (runAs). */
    void send(E entry);

    /**
     * Is this failure one that retrying cannot fix — the downstream ANSWERED and refused (a closed period, a rule)?
     * Such a row is dead-lettered at once instead of being retried {@link OutboxRelay#MAX_ATTEMPTS} times, so the
     * person who caused it learns why in seconds. Default {@code false}: every channel that does not opt in keeps
     * retrying everything, exactly as before (an outage, a timeout and a misconfigured secret all look like errors too).
     */
    default boolean permanent(Exception failure) { return false; }

    /** The words stored as the row's last error. Default the exception's message; a channel may extract the
     *  downstream's own reason (e.g. the {@code message} of a JSON error body) so it can be shown to a person. */
    default String describe(Exception failure) { return String.valueOf(failure.getMessage()); }
}

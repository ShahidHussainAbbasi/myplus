package com.myplus.common.audit;

import java.util.List;
import java.util.Optional;

/**
 * E4 — how {@link AuditEmitter} reaches one service's own {@code audit_outbox} table.
 *
 * <p>Four methods over the consumer's Spring Data repository, so common-audit needs no JPA repository of its
 * own and no {@code @EntityScan} change in any consumer. The service supplies its row type and its table;
 * the emitter owns when a row is written and when it is delivered.
 *
 * @param <E> the consumer's own {@code @Entity} extending {@link AbstractAuditOutbox}
 */
public interface AuditOutboxStore<E extends AbstractAuditOutbox> {

    /** A fresh, unsaved row of the consumer's entity type. */
    E newRow();

    Optional<E> find(Long id);

    /** Undelivered rows to re-drive — typically {@code findTop100ByStatusOrderByIdAsc("PENDING")}. */
    List<E> pending();

    E save(E row);
}

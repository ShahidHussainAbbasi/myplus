package com.myplus.common.outbox;

import java.util.List;

/**
 * D-6 — which outbox tables this service owns.
 *
 * <h3>One line per service, and that is the whole point</h3>
 * Every outbox in the platform shares the same delivery columns — {@code status}, {@code attempts},
 * {@code last_error}, {@code created_at}, {@code updated_at}, {@code organization_id}. That is
 * {@link OutboxEntry}'s contract expressed in SQL, and it was verified against {@code information_schema}
 * across all seven tables rather than assumed. So counting and re-driving needs nothing per service except
 * the table names: no repository, no entity change, no new method on the four existing
 * {@link OutboxDelivery} channels.
 *
 * <h3>⚠ This list is also the ALLOW-LIST</h3>
 * {@link OutboxHealthService} builds SQL from a table name that arrives in a request, and validates it
 * against this registry first. A re-drive endpoint that accepted an arbitrary table name would be an
 * arbitrary {@code UPDATE}. Nothing outside this list is reachable, whatever the caller sends.
 */
@FunctionalInterface
public interface OutboxHealthRegistry {

    /** Unqualified table names in this service's own schema, e.g. {@code List.of("audit_outbox", "gl_outbox")}. */
    List<String> outboxTables();
}

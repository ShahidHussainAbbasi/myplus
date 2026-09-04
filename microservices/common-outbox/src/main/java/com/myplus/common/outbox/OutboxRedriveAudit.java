package com.myplus.common.outbox;

/**
 * D-6 — how a re-drive reaches the audit trail, without this module knowing what an audit trail is.
 *
 * <h3>Why an SPI rather than a call to common-audit</h3>
 * {@code common-audit} depends on {@code common-outbox} — it is built ON the relay. A dependency the other
 * way would be a cycle. So the module that owns the re-drive publishes an event and the module that owns the
 * trail listens, which is the same shape as {@link OutboxDelivery} itself.
 *
 * <p>Optional: a service with no audit producer registers no bean and the re-drive still works. That is not
 * the {@code required = false} anti-pattern — there is genuinely nothing to record into, and the refusal to
 * re-drive is not what this interface guards. The re-drive itself is gated by {@code ROLE_ADMIN} and a
 * required reason either way.
 */
@FunctionalInterface
public interface OutboxRedriveAudit {

    /**
     * Record that somebody put failed rows back on the queue.
     *
     * @param table  the outbox table
     * @param count  how many rows were reset
     * @param reason the operator's own words — required by the endpoint, never blank here
     */
    void redriven(String table, int count, String reason);
}

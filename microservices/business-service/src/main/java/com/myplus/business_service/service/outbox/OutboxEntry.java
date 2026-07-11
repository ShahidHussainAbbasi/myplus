package com.myplus.business_service.service.outbox;

import java.time.LocalDateTime;

/**
 * The delivery-state contract a transactional-outbox row must expose so the shared {@link OutboxRelay} can drive it
 * (status machine + attempts/dead-letter), independent of the row's domain payload. Implemented by GlOutbox,
 * AuditOutbox, and any future producer's row — the payload columns stay private to each.
 */
public interface OutboxEntry {
    Long getId();
    String getStatus();
    void setStatus(String status);
    Integer getAttempts();
    void setAttempts(Integer attempts);
    void setLastError(String lastError);
    Long getUserId();
    Long getOrganizationId();
    void setUpdatedAt(LocalDateTime updatedAt);
}

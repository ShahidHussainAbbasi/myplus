package com.myplus.marketplace.entity;

import java.time.LocalDateTime;

import com.myplus.common.outbox.OutboxEntry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * MKT-1g — a journal for the operator's books, captured in the settlement's own transaction (V31). {@code payload}
 * is the WHOLE contract request as JSON (the expense_outbox shape), so a new contract field needs no new column.
 */
@Entity
@Table(name = "mkt_gl_outbox")
@Getter
@Setter
public class MarketplaceGlOutbox implements OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "event_key", nullable = false, length = 80)
    private String eventKey;

    @Column(name = "payload", nullable = false, length = 4000)
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Override
    public void setLastError(String lastError) {
        this.lastError = lastError == null ? null : (lastError.length() > 500 ? lastError.substring(0, 500) : lastError);
    }
}

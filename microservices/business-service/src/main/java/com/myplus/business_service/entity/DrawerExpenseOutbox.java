package com.myplus.business_service.entity;

import java.time.LocalDateTime;

import com.myplus.common.outbox.OutboxEntry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * EX-3 — a till pay-out on its way to expense-service, captured in the movement's own transaction.
 * {@code payload} is the whole {@code DrawerExpenseRequest} as JSON (never field-by-field — the gl_outbox lesson).
 */
@Entity
@Table(name = "drawer_expense_outbox")
@Getter @Setter
public class DrawerExpenseOutbox implements OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "movement_id", nullable = false)
    private Long movementId;

    @Column(name = "payload", nullable = false, length = 2000)
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

package com.myplus.finance.entity;

import java.time.LocalDateTime;

import com.myplus.common.outbox.OutboxEntry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FP-4c — "this supplier's figures changed; tell business" (V12). The figures are computed when it is sent. */
@Entity
@Table(name = "payable_balance_outbox")
@Getter @Setter
public class PayableBalanceOutbox implements OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "party_type", nullable = false, length = 16)
    private String partyType;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

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
    public void setLastError(String e) {
        this.lastError = e == null ? null : (e.length() > 500 ? e.substring(0, 500) : e);
    }
}

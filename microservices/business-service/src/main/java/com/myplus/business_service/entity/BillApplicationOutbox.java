package com.myplus.business_service.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.myplus.common.outbox.OutboxEntry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FP-5b — one bill's share of a Pay Supplier payment, on its way to expense-service (V77). */
@Entity
@Table(name = "bill_application_outbox")
@Getter @Setter
public class BillApplicationOutbox implements OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** What expense-service actually applied (≤ amount when the bill was partly paid from Expenses in between). */
    @Column(name = "applied", precision = 19, scale = 2)
    private BigDecimal applied;

    @Column(name = "client_ref", nullable = false, length = 100)
    private String clientRef;

    @Column(name = "method", length = 16)
    private String method;

    @Column(name = "paid_on")
    private LocalDate paidOn;

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

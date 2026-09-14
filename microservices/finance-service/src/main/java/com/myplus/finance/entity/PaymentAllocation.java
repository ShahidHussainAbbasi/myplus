package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * How much of a payment was applied to a specific source document (an invoice = a business-service
 * CustomerHistory). The sum of allocations ≤ the payment amount; any remainder is on-account credit.
 */
@Entity
@Table(name = "payment_allocations", indexes = {
        @Index(name = "idx_alloc_doc", columnList = "doc_type,doc_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "doc_type", length = 20)
    private String docType;      // INVOICE (Phase 1)

    @Column(name = "doc_id")
    private Long docId;          // CustomerHistory id

    @Column(name = "doc_no")
    private String docNo;        // invoice number

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    /**
     * OPTIMISTIC LOCK on the allocation row (BLK-0c) — FORWARD protection, as on {@link Payment#getVersion()}.
     *
     * <p>⚠ Nothing updates an allocation today: they are only ever inserted, cascaded from a new Payment. On
     * its own row and not only the parent's because re-pointing a receipt at a different invoice — the edit
     * most likely to arrive first — would write THIS row and leave the parent untouched, so a lock on the
     * parent alone would miss exactly the contention it exists for.
     *
     * <p><b>Not {@code createdAt}</b>: it is set BY the write being validated, has second fidelity, and two
     * writes inside one second compare equal. A counter JPA owns has none of those ambiguities.
     *
     * <p>⚠ {@code NOT NULL DEFAULT 0} in V6 — a NULL version makes Hibernate treat an existing row as
     * TRANSIENT and INSERT it, which on this table would duplicate money.
     */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version;

}

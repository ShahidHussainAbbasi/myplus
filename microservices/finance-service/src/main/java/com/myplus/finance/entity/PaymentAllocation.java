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
}

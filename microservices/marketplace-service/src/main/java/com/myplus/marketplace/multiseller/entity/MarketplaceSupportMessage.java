package com.myplus.marketplace.multiseller.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1f — one message on a support case (V30). {@code visibleToCustomer = false} is an internal note, or a seller's
 * raw reply before the operator relays it; the customer's view never reads those rows.
 */
@Entity
@Table(name = "mkt_support_message")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceSupportMessage {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String OPERATOR = "OPERATOR";
    public static final String SELLER = "SELLER";
    public static final String SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "author_kind", nullable = false, length = 16)
    private String authorKind;

    @Column(name = "author_ref")
    private Long authorRef;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "visible_to_customer", nullable = false)
    private boolean visibleToCustomer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}

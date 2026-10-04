package com.myplus.marketplace.multiseller.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1f — the customer's one conversation with MaxTheService about one order (V30). The customer never talks to a
 * seller (R8.2): the operator tasks the seller and relays the answer.
 */
@Entity
@Table(name = "mkt_support_case")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceSupportCase {

    public static final String OPEN = "OPEN";
    public static final String WAITING_SELLER = "WAITING_SELLER";
    public static final String WAITING_CUSTOMER = "WAITING_CUSTOMER";
    public static final String RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, length = 32)
    private String caseNo;

    @Column(name = "mkt_order_id", nullable = false)
    private Long mktOrderId;

    @Column(name = "seller_org_id", nullable = false)
    private Long sellerOrgId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "topic", nullable = false, length = 16)
    private String topic;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "urgent", nullable = false)
    private boolean urgent;

    @Column(name = "resolution", length = 500)
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}

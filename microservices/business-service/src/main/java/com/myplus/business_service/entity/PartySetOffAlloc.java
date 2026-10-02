package com.myplus.business_service.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * DR-4 — exactly which invoice or bill a set-off cleared, and by how much. A reversal re-opens these rows and nothing
 * else; they are never re-derived from the documents, which later payments change.
 */
@Entity
@Table(name = "party_setoff_alloc")
@Getter @Setter
public class PartySetOffAlloc {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String VENDOR = "VENDOR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setoff_id", nullable = false)
    private Long setOffId;

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "doc_no", length = 60)
    private String docNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}

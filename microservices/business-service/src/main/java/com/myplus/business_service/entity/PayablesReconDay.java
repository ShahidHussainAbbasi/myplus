package com.myplus.business_service.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/** FP-6a — one tenant's payables reconciliation for one day (V78). See PayablesReconciliationService. */
@Entity
@Table(name = "payables_recon_day")
@Getter
@Setter
public class PayablesReconDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "recon_day", nullable = false)
    private LocalDate reconDay;

    /** What business says its suppliers are owed for purchases (−Σ due over supplier purchases; due = paid − bill). */
    @Column(name = "business_owed", precision = 19, scale = 2)
    private BigDecimal businessOwed;

    /** finance's PURCHASE documents, net (the mirror of the line above). */
    @Column(name = "finance_purchase", precision = 19, scale = 2)
    private BigDecimal financePurchase;

    /** finance's whole supplier ledger, net of advances (purchases + expense bills). */
    @Column(name = "finance_net", precision = 19, scale = 2)
    private BigDecimal financeNet;

    @Column(name = "gl_payable", precision = 19, scale = 2)
    private BigDecimal glPayable;

    /** businessOwed − financePurchase, BEFORE any repair. */
    @Column(name = "shadow_diff", precision = 19, scale = 2)
    private BigDecimal shadowDiff;

    /** glPayable − financeNet, BEFORE any repair. */
    @Column(name = "ledger_diff", precision = 19, scale = 2)
    private BigDecimal ledgerDiff;

    @Column(name = "docs_resent", nullable = false)
    private int docsResent;

    @Column(name = "ledger_aligned", precision = 19, scale = 2)
    private BigDecimal ledgerAligned;

    /** No repair needed: both differences were zero when the day was checked. */
    @Column(name = "clean", nullable = false)
    private Boolean clean = false;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "ran_at")
    private LocalDateTime ranAt;
}

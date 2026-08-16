package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit #4: a queued GL posting event (transactional outbox). Written in the same TX as the sale/purchase/return/
 * edit so the event can't be lost; delivered to finance-service by GlOutboxService (afterCommit + @Scheduled relay).
 * Columns mirror commerce-contracts PostingEventRequest. Tenant-scoped (org/user carried for the relay's runAs).
 */
@Data
@Entity
@Table(name = "gl_outbox", indexes = { @Index(name = "idx_outbox_pending", columnList = "status,id") })
public class GlOutbox implements com.myplus.common.outbox.OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;   // SALE | PURCHASE | SALE_RETURN | PURCHASE_RETURN

    // Audit #5: a stable per-event UUID so finance dedups a duplicate outbox delivery (closes the #4 dup-journal window).
    @Column(name = "event_key", length = 64)
    private String eventKey;

    private String ref;

    @Column(name = "grand_total", precision = 19, scale = 2)
    private BigDecimal grandTotal;
    @Column(name = "sub_total", precision = 19, scale = 2)
    private BigDecimal subTotal;
    @Column(name = "tax_total", precision = 19, scale = 2)
    private BigDecimal taxTotal;
    @Column(precision = 19, scale = 2)
    private BigDecimal cost;
    @Column(name = "paid_amount", precision = 19, scale = 2)
    private BigDecimal paidAmount;

    /** SF-5 Model B: store-credit portion of the event (redeemed on SALE / issued on SALE_RETURN → GL 2200). */
    @Column(name = "store_credit", precision = 19, scale = 2)
    private BigDecimal storeCredit;

    /**
     * Whole-document concession → Dr 4200 Sales Discount (V40).
     *
     * <p><b>This column is the reason D-4 never worked.</b> The posting rule in finance has debited 4200
     * since D-4 shipped, and business-service has passed {@code .discountTotal(ch.getTradeDiscount())} to
     * {@code enqueue} ever since — but the outbox is a PERSISTED table, and it had no column to put the value
     * in. {@code enqueue} silently dropped it and {@code toReq} rebuilt the event without it, so finance
     * always received zero and 4200 stayed empty across every tenant.
     *
     * <p>The lesson worth keeping: an outbox that rebuilds its payload from named columns is a place where a
     * new field is dropped in SILENCE. Nothing fails; the number is just quietly gone.
     */
    @Column(name = "discount_total", precision = 19, scale = 2)
    private BigDecimal discountTotal;

    /**
     * Delivery charged to the customer → Cr 4300 Delivery Income (V40).
     *
     * <p>Unlike the discount, dropping this one does not fail quietly: delivery rides inside
     * {@code grandTotal} but not inside {@code subTotal}/{@code taxTotal}, so an event without it produces a
     * journal short by exactly the fee, which {@code GlService.validate} rejects — the sale posts NO journal
     * at all. Losing the number loudly turned out to be more useful than losing it silently.
     */
    @Column(name = "shipping_fee", precision = 19, scale = 2)
    private BigDecimal shippingFee;

    @Column(length = 30)
    private String method;

    @Column(nullable = false, length = 20)
    private String status;      // PENDING | POSTED | FAILED

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "organization_id")
    private Long organizationId;
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

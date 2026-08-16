package com.myplus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D4 — what happened when a parcel reached the shop, as keyed by the warehouse admin (V21).
 *
 * <h3>The driver has no device, and this class is shaped by that</h3>
 * Ahsan carries printed invoices, the shop signs them, and Javed keys the outcome on his return. So <b>the
 * signed paper invoice is the proof of delivery</b> and this is the keyed summary of it — never a substitute
 * for it. Every field name is chosen so the two cannot later be confused:
 *
 * <ul>
 *   <li>{@link #recordedAt} — when the admin KEYED it, possibly hours after the goods arrived. There is
 *       deliberately <b>no {@code deliveredAt}</b>: the system did not observe that moment, and a column with
 *       that name would be read as though it had.</li>
 *   <li>{@link #recordedByName} — who KEYED it. <b>Not the driver.</b> Attributing this to the person who was
 *       at the door would record an observation as coming from someone who did not make it.</li>
 *   <li>{@link #deliveredBy} — the driver, as free text. That IS worth knowing; it is a note, not an identity,
 *       and it is not evidence.</li>
 * </ul>
 */
@Entity
@Table(name = "delivery_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "shipment_id")
    private Long shipmentId;

    /**
     * O7 D5 — the trade account this parcel was billed to, <b>stamped at keying</b> from the order (V22).
     *
     * <p>A day-end remittance posts a receipt per collection, and a receipt needs somebody to clear. Deriving
     * it from {@code orders} on every settlement read would be the derive-on-read shape this platform stamps
     * instead, and would put a join on a list that is read every working day. NULL means the order had no
     * trade account behind it (a storefront order), and a settlement refuses such a collection by name rather
     * than posting the money to a guess.
     */
    @Column(name = "customer_id")
    private Long customerId;

    /** Stamped alongside the id, per V19's rule: a settlement outlives the outlet row being renamed or merged. */
    @Column(name = "customer_name")
    private String customerName;

    /** The invoice THIS parcel went out on — the one any shortfall is credited against. */
    @Column(name = "invoice_no")
    private String invoiceNo;

    /** DELIVERED (all of it) | PARTIAL (some came back) | REFUSED (none taken). Derived, never typed. */
    @Column(name = "outcome", length = 24)
    private String outcome;

    /** PAID | PART_PAID | CREDIT — how the shop settled. Most of this trade is CREDIT. */
    @Column(name = "settlement", length = 24)
    private String settlement;

    @Column(name = "amount_collected", precision = 19, scale = 2)
    private java.math.BigDecimal amountCollected;

    /** The {@code CRN-} numbers raised for what came back, so the delivery and its credit notes stay linked. */
    @Column(name = "credit_notes", length = 500)
    private String creditNotes;

    /**
     * O7 D5 — the remittance this collection was handed over in. <b>NULL means OPEN</b>: cash the company
     * believes a driver is still holding.
     *
     * <p>One column, on purpose. A collection cannot belong to two settlements, so "remitted at most once" is a
     * structural guarantee rather than a check somebody could forget to write — and the claim that sets it is
     * an {@code UPDATE … WHERE settlement_id IS NULL}, so two admins settling the same driver at once cannot
     * both win.
     */
    @Column(name = "settlement_id")
    private Long settlementId;

    /**
     * The receipt business-service raised when this collection was remitted.
     *
     * <p>Kept so the delivery, the invoice it was collected against and the receipt that cleared it stay linked
     * from either end — a shopkeeper asking "you say I still owe this" is answered from one row.
     */
    @Column(name = "receipt_no", length = 64)
    private String receiptNo;

    @Column(name = "delivered_by")
    private String deliveredBy;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "recorded_by_name")
    private String recordedByName;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "recorded_at")
    private java.time.LocalDateTime recordedAt;
}

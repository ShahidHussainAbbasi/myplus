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

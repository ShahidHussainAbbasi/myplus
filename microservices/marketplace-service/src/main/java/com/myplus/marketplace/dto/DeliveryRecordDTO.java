package com.myplus.marketplace.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

import com.myplus.marketplace.entity.DeliveryRecord;

/**
 * OMS O7 D4/D5 — one keyed delivery outcome, at the boundary.
 *
 * <h3>Why this exists</h3>
 * {@code GET /orders/{id}/deliveries} was answering with {@code List<DeliveryRecord>} — a JPA entity from a
 * controller, which §1.5 forbids and which shipped {@code organizationId} and the raw row id to the browser.
 * D1 caught and fixed exactly this (§8.1b #4) and D4 reintroduced it. Field names are identical to the
 * entity's, so no reader has to change.
 *
 * <h3>The D5 additions are the point of touching it again</h3>
 * {@link #settlementId} and {@link #receiptNo} are what turn a delivery record into an auditable collection: a
 * row with an amount and no settlement is cash somebody is still holding. A persisted field no read returns is
 * invisible (standard D10), and that is precisely how {@code settlement} and {@code amountCollected} sat unused
 * between D4 and D5.
 */
@Data
public class DeliveryRecordDTO {

    private Long id;
    private Long orderId;
    private Long shipmentId;
    private String invoiceNo;

    /** DELIVERED | PARTIAL | REFUSED — derived from the quantities, never typed. */
    private String outcome;

    /** PAID | PART_PAID | CREDIT. */
    private String settlement;
    private BigDecimal amountCollected;

    /** The trade account this parcel was billed to, stamped at keying (V22). */
    private Long customerId;
    private String customerName;

    /** {@code CRN-} numbers raised for what came back. */
    private String creditNotes;

    /** NULL = this collection has NOT been handed over and counted yet. */
    private Long settlementId;

    /** The receipt raised when it was. */
    private String receiptNo;

    /** The driver — a note, not an identity and not evidence. */
    private String deliveredBy;

    /** Who KEYED it. Never the driver: the system must not record an observation as coming from someone else. */
    private String recordedByName;

    private String note;

    /** When it was KEYED — deliberately not "delivered at", which nobody observed. */
    private LocalDateTime recordedAt;

    public static DeliveryRecordDTO of(DeliveryRecord d) {
        DeliveryRecordDTO x = new DeliveryRecordDTO();
        x.id = d.getId();
        x.orderId = d.getOrderId();
        x.shipmentId = d.getShipmentId();
        x.invoiceNo = d.getInvoiceNo();
        x.outcome = d.getOutcome();
        x.settlement = d.getSettlement();
        x.amountCollected = d.getAmountCollected();
        x.customerId = d.getCustomerId();
        x.customerName = d.getCustomerName();
        x.creditNotes = d.getCreditNotes();
        x.settlementId = d.getSettlementId();
        x.receiptNo = d.getReceiptNo();
        x.deliveredBy = d.getDeliveredBy();
        x.recordedByName = d.getRecordedByName();
        x.note = d.getNote();
        x.recordedAt = d.getRecordedAt();
        return x;
    }
}

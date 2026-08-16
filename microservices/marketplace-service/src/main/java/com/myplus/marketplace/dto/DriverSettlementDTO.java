package com.myplus.marketplace.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

import com.myplus.marketplace.entity.DriverSettlement;

/**
 * OMS O7 D5 — a day-end remittance, in and out.
 *
 * <h3>What the client may say, and what it may not</h3>
 * In: which collections, how much cash was counted, the deposit reference, the date and a note. <b>Out only:
 * the declared total and the variance</b> — the server adds up what the driver's own keyed entries come to, and
 * a client-supplied total would be OMS-5 in a new place (the channel says what happened, the server says what
 * it is worth). The fields are on one class because they are one document; the ones the server owns are marked.
 */
@Data
public class DriverSettlementDTO {

    // ── in ───────────────────────────────────────────────────────────────────────────────────────────────
    /**
     * The collections being handed over. Re-read scoped and still-open inside the transaction; ids the caller
     * does not own, or that somebody else already remitted, simply are not there.
     */
    private List<Long> deliveryIds;

    /** What was physically in the bag. Required — a settlement with no count has settled nothing. */
    private BigDecimal countedAmount;

    /** The day being settled. Defaults to today; carried onto every receipt, so the period lock applies to it. */
    private LocalDate settlementDate;

    /** Bank slip / safe-drop reference. */
    private String depositReference;

    /** Required when the variance is non-zero — a short bag nobody explained is the failure B1 exists to catch. */
    private String note;

    // ── out ──────────────────────────────────────────────────────────────────────────────────────────────
    private Long id;
    private String settlementNo;

    /** Derived from the claimed collections, not accepted from the client — a remittance may not mix two drivers. */
    private String driverName;

    /** SERVER-owned: what the driver's own keyed entries add up to. */
    private BigDecimal declaredAmount;

    /** SERVER-owned: {@code counted − declared}. Negative is short — the same sign the till's Z report uses. */
    private BigDecimal varianceAmount;

    private Integer collectionCount;
    private String settledByName;
    private LocalDateTime settledAt;

    /** The receipts this remittance raised, one per collection — the proof the money reached AR. */
    private List<String> receipts;

    /** Present on the detail read: what was swept up. */
    private List<DeliveryRecordDTO> collections;

    public static DriverSettlementDTO of(DriverSettlement s) {
        DriverSettlementDTO x = new DriverSettlementDTO();
        x.id = s.getId();
        x.settlementNo = s.getSettlementNo();
        x.driverName = s.getDriverName();
        x.settlementDate = s.getSettlementDate();
        x.declaredAmount = s.getDeclaredAmount();
        x.countedAmount = s.getCountedAmount();
        x.varianceAmount = s.getVarianceAmount();
        x.collectionCount = s.getCollectionCount();
        x.depositReference = s.getDepositReference();
        x.note = s.getNote();
        x.settledByName = s.getSettledByName();
        x.settledAt = s.getSettledAt();
        return x;
    }
}

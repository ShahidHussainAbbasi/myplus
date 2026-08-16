package com.myplus.marketplace.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
 * OMS O7 D5 — one driver handing over one day's cash (V22). Closes backlog B1.
 *
 * <h3>Why this row exists at all</h3>
 * Ahsan has no device (§6 D-5), so the paper invoices and the cash bag are the only controls on the money. D4
 * records what he says each shop paid; <b>this is the act that compares that to what is actually in the bag,
 * and it is what posts the receipts.</b> Making the posting depend on the count is what gives the count teeth:
 * if the receipts posted at keying time, the reconciliation would be a report nobody has to run, which is
 * precisely B1's complaint.
 *
 * <h3>There is no draft, and no status column</h3>
 * A half-finished remittance would be a row claiming custody of cash while posting none of it — strictly worse
 * than no row, because the collections would leave the open list without reaching the books. One act, one
 * transaction, confirmed on creation. (The till's {@code CashierShift} does have an open state, correctly: a
 * shift accumulates events over hours. A remittance is a single handover.)
 *
 * <h3>What is deliberately NOT here</h3>
 * A journal for the variance. {@link #varianceAmount} is recorded and reported and never posted, exactly as
 * {@code CashierShift.variance} is — this platform has no cash-with-drivers clearing account, and inventing one
 * inside a delivery feature would be a second money path.
 */
@Entity
@Table(name = "driver_settlement")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    /** Per-org counter. MAX+1 inside the creating transaction, made safe by UNIQUE(organization_id, settlement_seq). */
    @Column(name = "settlement_seq", nullable = false)
    private Long settlementSeq;

    /** {@code DS-<seq>} — what an admin says out loud. */
    @Column(name = "settlement_no", length = 32)
    private String settlementNo;

    /**
     * The driver, as the free text D4 defined it — <b>a note, not an identity</b>. A settlement may not mix two
     * drivers: a remittance is one person handing over one bag, and two names in this column would make the
     * variance unattributable.
     */
    @Column(name = "driver_name")
    private String driverName;

    /** The day being settled. Carried onto every receipt as its {@code paidOn}, so the period lock applies to it. */
    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    /** What the driver's own keyed entries add up to. Computed from the claimed rows — never accepted from the client. */
    @Column(name = "declared_amount", precision = 19, scale = 2)
    private BigDecimal declaredAmount;

    /** What was physically in the bag. */
    @Column(name = "counted_amount", precision = 19, scale = 2)
    private BigDecimal countedAmount;

    /**
     * {@code counted − declared}. NEGATIVE is short.
     *
     * <p>Same formula and same sign convention as {@code ShiftService.closeShift}, deliberately not inverted:
     * an admin who reads both the till's Z report and this must not have to remember which way round each one
     * is.
     */
    @Column(name = "variance_amount", precision = 19, scale = 2)
    private BigDecimal varianceAmount;

    @Column(name = "collection_count")
    private Integer collectionCount;

    /** Bank slip / safe-drop reference for the money once it left the admin's hands. */
    @Column(name = "deposit_reference", length = 120)
    private String depositReference;

    /** Required when the variance is non-zero: a short bag nobody explained is the failure B1 exists to catch. */
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "settled_by_user_id")
    private Long settledByUserId;

    /** Stamped, per V19's rule — "who signed off a short bag" must stay answerable after they leave. */
    @Column(name = "settled_by_name")
    private String settledByName;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}

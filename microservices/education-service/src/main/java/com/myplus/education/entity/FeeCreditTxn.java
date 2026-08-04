package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Slice 0.2b: one movement in a student's fee-credit ledger — money the school holds on the guardian's behalf,
 * normally an overpayment carried forward to the next charge.
 *
 * Append-only and SIGNED: positive issues credit, negative redeems it. The balance is the sum of this history, so
 * it always explains itself; {@code Student.creditBalance} is only a cached projection of it.
 *
 * Mirrors business's {@code store_credit_txn} — the same rules run over both via common-credit — but lives in
 * myplusdb_education, because services share logic, not tables.
 */
@Entity
@Table(name = "fee_credit_txn", indexes = {
        @Index(name = "idx_fee_credit_org_student", columnList = "organization_id,student_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FeeCreditTxn {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    /** Signed: + issues credit, − redeems it. */
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    /** OVERPAYMENT | REDEEM — why the movement happened, for the audit trail. */
    @Column(length = 30)
    private String reason;

    /** The fee record this movement relates to, so a credit can be traced back to the collection. */
    @Column(length = 64)
    private String ref;

    private LocalDateTime dated;
}

package com.myplus.finance.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * FP-1 — one supplier payable document in finance's subledger (V9). Party-agnostic: the supplier is referenced as
 * (partyType, partyId), never owned here. In FP-1/FP-2 the source reports snapshots; open = amount − paid.
 */
@Entity
@Table(name = "payable_doc")
@Getter @Setter
public class PayableDoc {

    public static final String OPEN = "OPEN", SETTLED = "SETTLED", VOID = "VOID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "party_type", nullable = false, length = 16)
    private String partyType;

    @Column(name = "party_id")
    private Long partyId;

    @Column(name = "party_name", length = 160)
    private String partyName;

    @Column(name = "source", nullable = false, length = 24)
    private String source;

    @Column(name = "source_ref", nullable = false, length = 64)
    private String sourceRef;

    @Column(name = "source_version", nullable = false)
    private Long sourceVersion = 0L;

    @Column(name = "doc_no", length = 64)
    private String docNo;

    @Column(name = "doc_date")
    private LocalDate docDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid", nullable = false, precision = 19, scale = 2)
    private BigDecimal paid = BigDecimal.ZERO;

    /** FP-4a — the bill as issued (gross, before returns): the statement's BILL line. Null → {@link #amount}. */
    @Column(name = "issued_amount", precision = 19, scale = 2)
    private BigDecimal issuedAmount;

    /** FP-4a — when the supplier expects payment; null = age by {@link #docDate}. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** What is still owed on this document (never negative; zero for a void). */
    public BigDecimal open() {
        if (VOID.equals(status)) return BigDecimal.ZERO;
        BigDecimal o = amount.subtract(paid == null ? BigDecimal.ZERO : paid);
        return o.signum() < 0 ? BigDecimal.ZERO : o;
    }
}

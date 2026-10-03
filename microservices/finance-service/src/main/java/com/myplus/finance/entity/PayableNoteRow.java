package com.myplus.finance.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FP-4a — a debit note against a payable document (V11): the statement trail only, never read by a balance. */
@Entity
@Table(name = "payable_note")
@Getter @Setter
public class PayableNoteRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "payable_doc_id", nullable = false)
    private Long payableDocId;

    @Column(name = "note_no", length = 64)
    private String noteNo;

    @Column(name = "note_date")
    private LocalDate noteDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}

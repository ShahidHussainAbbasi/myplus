package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A DEBIT NOTE — the document for goods returned to a supplier (slice b2b-P3c = requirement #1).
 *
 * <p>Created because the supplier side had no document at all: {@code purchaseReturn} adjusted stock and the
 * payable and posted to the GL, but left nothing you could hand to a vendor or reconcile against. A supplier
 * matching your return against their credit note needs a number that is yours and unambiguous.
 *
 * <p>{@code debitNoteNo} is this document's own identity; {@code purchaseInvoiceNo} is the bill it
 * <b>reverses</b>. Keeping both is the accounting requirement — a debit note references what it reverses
 * rather than borrowing its number, which is precisely the defect #1 names.
 */
@Entity
@Table(name = "purchase_return")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PurchaseReturn {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Per-org running number. UNIQUE(organization_id, debit_note_seq) is what makes MAX+1 allocation safe. */
    @Column(name = "debit_note_seq")
    private Long debitNoteSeq;

    /** Display form, e.g. {@code DBN-000007}. Formatted by {@code InvoiceNumbers.debitNote}. */
    @Column(name = "debit_note_no", length = 32)
    private String debitNoteNo;

    @Column(name = "purchase_id")
    private Long purchaseId;

    /** The bill this reverses — the REFERENCE, not this document's identity. */
    @Column(name = "purchase_invoice_no", length = 64)
    private String purchaseInvoiceNo;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "vender_id")
    private Long venderId;

    @Column(name = "quantity", precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "reason")
    private String reason;

    /** Gross value returned to the supplier (goods + input tax), matching the GL reversal. */
    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "dated")
    private LocalDateTime dated;
}

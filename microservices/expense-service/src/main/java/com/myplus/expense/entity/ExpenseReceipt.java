package com.myplus.expense.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** EX-5 — one receipt's metadata (the file itself is in the ReceiptStore under {@link #storeKey}). Flyway V8. */
@Entity
@Table(name = "expense_receipt")
@Getter @Setter
public class ExpenseReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** NULL until the expense it was uploaded for is saved. */
    @Column(name = "voucher_id")
    private Long voucherId;

    @Column(name = "store_key", nullable = false, length = 200)
    private String storeKey;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "original_name", length = 160)
    private String originalName;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "removed_by")
    private Long removedBy;
}

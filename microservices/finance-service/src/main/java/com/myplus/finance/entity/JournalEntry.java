package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F3 (GL): one balanced double-entry transaction — ≥2 {@link JournalLine}s with Σdebit = Σcredit (enforced on post).
 * Immutable once POSTED (corrections are a new reversing entry). Carries its source event (SALE/PURCHASE/…) for
 * traceability back to the operational document. Org-scoped.
 */
@Entity
@Table(name = "journal_entries", indexes = {
        @Index(name = "idx_je_org_date", columnList = "organization_id,entry_date")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(length = 20)
    private String source;        // SALE | PURCHASE | RECEIPT | PAYMENT | MANUAL

    @Column(name = "source_ref")
    private String sourceRef;     // invoiceNo / voucherNo / free text

    @Column(name = "memo")
    private String memo;

    @Column(length = 20)
    private String status;        // POSTED (immutable)

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<JournalLine> lines = new ArrayList<>();

    public void addLine(JournalLine l) {
        l.setEntry(this);
        this.lines.add(l);
    }
}

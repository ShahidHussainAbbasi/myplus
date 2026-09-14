package com.myplus.finance.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DOC-INT B — the last receipt / payment-voucher number issued per organisation and direction (V7).
 *
 * <p>Mapped so {@code ddl-auto=validate} checks the columns; never written through JPA. Every change goes through
 * {@code OrgDocumentSeqRepo}'s native statements, because the row lock taken by the UPDATE is the whole mechanism
 * and a load-increment-flush would be the {@code COUNT + 1} race again.
 */
@Entity
@Table(name = "org_document_seq")
@IdClass(OrgDocumentSeqId.class)
@Getter @Setter @NoArgsConstructor
public class OrgDocumentSeq {

    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Id
    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    @Column(name = "next_val", nullable = false)
    private Long nextVal;

    @Column(name = "updated")
    private LocalDateTime updated;
}

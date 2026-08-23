package com.myplus.business_service.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import lombok.Data;

/**
 * One per-org document counter: "tenant 13 has issued 42 credit notes".
 *
 * <p>Mapped mainly so Hibernate knows the table exists and {@code ddl-auto} leaves it alone. Nothing reads or
 * writes it through JPA — see {@link com.myplus.business_service.repository.OrgDocumentSeqRepo} for why every
 * operation is a native statement: a JPA read-modify-write would reintroduce the very race this table exists
 * to remove.
 */
@Data
@Entity
@Table(name = "org_document_seq")
@IdClass(OrgDocumentSeqId.class)
public class OrgDocumentSeq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Id
    @Column(name = "doc_type", nullable = false, length = 16)
    private String docType;

    /** The LAST number issued, so a fresh counter is 0 and the first document is 1. */
    @Column(name = "next_val", nullable = false)
    private Long nextVal;

    @Column(name = "updated")
    private LocalDateTime updated;
}

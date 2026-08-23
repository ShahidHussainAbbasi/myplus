package com.myplus.business_service.entity;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link OrgDocumentSeq}: the counter is per organisation AND per document type. */
public class OrgDocumentSeqId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long organizationId;
    private String docType;

    public OrgDocumentSeqId() {}

    public OrgDocumentSeqId(Long organizationId, String docType) {
        this.organizationId = organizationId;
        this.docType = docType;
    }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrgDocumentSeqId other)) return false;
        return Objects.equals(organizationId, other.organizationId) && Objects.equals(docType, other.docType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(organizationId, docType);
    }
}

package com.myplus.business_service.service;

/**
 * The document series business-service numbers, each its own per-org counter in {@code org_document_seq}.
 *
 * <p>EX-0c — these lived on business-service's own {@code DocumentNumberService}. The allocator moved to
 * {@code common-docnum} so finance and expense share one copy; the NAMES stay here, because which documents a
 * POS issues is this service's business and no other's. The string values are the {@code doc_type} column's
 * existing keys and must never change — a renamed key would start a second series at 1.
 */
public final class DocType {

    public static final String CREDIT_NOTE = "CREDIT_NOTE";
    public static final String DEBIT_NOTE = "DEBIT_NOTE";
    public static final String QUOTE = "QUOTE";
    public static final String INVOICE = "INVOICE";
    public static final String PLAN = "PLAN";
    /**
     * OB-1 — opening balances get their OWN series (OB-000001), never the invoice one.
     *
     * An opening balance consuming an INV- number would leave a gap in the shop's invoice sequence at
     * exactly the point an auditor looks hardest: the migration. The series is per-org like the others.
     */
    public static final String OPENING = "OPENING";

    private DocType() { }
}

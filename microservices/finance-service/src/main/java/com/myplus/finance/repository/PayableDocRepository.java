package com.myplus.finance.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.finance.entity.PayableDoc;

/** FP-1 — the payables subledger, always read inside one tenant (idx_payable_doc_party / uq_payable_doc_source). */
public interface PayableDocRepository extends JpaRepository<PayableDoc, Long> {

    Optional<PayableDoc> findByOrganizationIdAndSourceAndSourceRef(Long organizationId, String source, String sourceRef);

    /** Σ open across the tenant's live documents (a void contributes nothing). */
    @Query("select coalesce(sum(d.amount - d.paid), 0) from PayableDoc d "
         + "where d.organizationId = :org and d.status = 'OPEN'")
    BigDecimal sumOpen(@Param("org") Long org);

    /**
     * FP-6a — Σ(amount − paid) over every NON-VOID document: what the supplier ledger nets to, advances included. An
     * overpaid bill is SETTLED with a negative balance, so {@link #sumOpen} leaves it out while GL 2000 carries the
     * overpayment — a false difference for every tenant holding an advance. Optional source filter (null = all).
     */
    @Query("select coalesce(sum(d.amount - d.paid), 0) from PayableDoc d "
         + "where d.organizationId = :org and d.status <> 'VOID' and (:source is null or d.source = :source)")
    BigDecimal sumNet(@Param("org") Long org, @Param("source") String source);

    /**
     * Per supplier, Σ(amount − paid) over every non-void document — an overpaid line counts negative, so a supplier
     * paid more than billed nets below zero (an ADVANCE). Business shows the same net, floored at zero.
     */
    @Query("select d.partyId, max(d.partyName), sum(d.amount - d.paid) from PayableDoc d "
         + "where d.organizationId = :org and d.status <> 'VOID' group by d.partyId")
    List<Object[]> netBySupplier(@Param("org") Long org);

    /** FP-3 — as {@link #netBySupplier}, per SOURCE (PURCHASE, EXPENSE_BILL): each feeding module reconciles to its own. */
    @Query("select d.source, d.partyId, sum(d.amount - d.paid) from PayableDoc d "
         + "where d.organizationId = :org and d.status <> 'VOID' group by d.source, d.partyId")
    List<Object[]> netBySourceAndSupplier(@Param("org") Long org);

    /** FP-4a — every document of one supplier, any status, oldest first (the statement's BILL lines). */
    List<PayableDoc> findByOrganizationIdAndPartyTypeAndPartyIdOrderByDocDateAscIdAsc(Long organizationId, String partyType, Long partyId);

    /** FP-4c — Σ still open on one supplier's NON-purchase documents (expense bills): what business cannot see. */
    @Query("select coalesce(sum(d.amount - d.paid), 0) from PayableDoc d where d.organizationId = :org "
         + "and d.partyType = :type and d.partyId = :party and d.source <> 'PURCHASE' and d.status = 'OPEN'")
    BigDecimal otherOpen(@Param("org") Long org, @Param("type") String type, @Param("party") Long party);

    /** FP-4c — one supplier's net across every live document; below zero = paid AHEAD (an advance). */
    @Query("select coalesce(sum(d.amount - d.paid), 0) from PayableDoc d where d.organizationId = :org "
         + "and d.partyType = :type and d.partyId = :party and d.status <> 'VOID'")
    BigDecimal netOf(@Param("org") Long org, @Param("type") String type, @Param("party") Long party);

    @Query("select d from PayableDoc d where d.organizationId = :org and d.status = 'OPEN' order by d.partyName, d.docDate")
    List<PayableDoc> findOpen(@Param("org") Long org);
}
